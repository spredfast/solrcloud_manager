package com.whitepages.cloudmanager.operation

import java.nio.file.{Paths, Files, Path}
import java.util

import com.whitepages.cloudmanager.action._
import org.apache.solr.client.solrj.impl.CloudSolrServer
import com.whitepages.cloudmanager.state.{SolrReplica, ClusterManager}
import org.apache.solr.common.cloud.Replica
import scala.annotation.tailrec
import com.whitepages.cloudmanager.ManagerSupport

object Operations extends ManagerSupport {

  /**
   * Generates an operation to handle the expected data deployment scheme. So given our expected build strategy:
   * 1. Add an indexer node to the cluster
   * 2. Create a collection using only that node
   * 3. Index data onto that node
   * 4. Replicate the resulting shards across the other nodes in the cluster
   * 5. Remove the indexer from the collection
   * 6. Remove the indexer node from the cluster
   * This call generates an Operation that handles #4-5.
   *
   * @param clusterManager
   * @param collection
   * @param slicesPerNode
   * @return An Operation that populates a cluster from a collection that exists on a single node
   */
  def populateCluster(clusterManager: ClusterManager, collection: String, slicesPerNode: Int): Operation = {
    val state = clusterManager.currentState

    val nodesWithoutCollection = state.liveNodes -- state.nodesWithCollection(collection)
    assert(state.liveNodes.size - nodesWithoutCollection.size == 1, "Should be expanding from a single node into a cluster of nodes")
    val sliceNames = state.replicasFor(collection).map(_.sliceName)
    assert(sliceNames.size % slicesPerNode == 0, "Presume slices can be divided evenly using slicesPerNode")
    assert(sliceNames.size >= slicesPerNode, s"Can't have more slices per node ($slicesPerNode) than the total number of slices (${sliceNames.size})")
    val nodesPerSet = sliceNames.size / slicesPerNode // number of nodes necessary for a complete index
    val replicationFactor = nodesWithoutCollection.size / nodesPerSet
    assert(nodesWithoutCollection.size % nodesPerSet == 0, s"Can make complete replica sets using the available nodes and slicesPerNode")


    val assignments = nodesWithoutCollection.toSeq.zip(List.fill(replicationFactor)(sliceNames).flatten.grouped(slicesPerNode).toList)
    comment.info(s"Populate Operation Found: Available Nodes - ${nodesWithoutCollection.size}, Replication factor - $replicationFactor, nodesPerSet - $nodesPerSet")
    val actions = for {(node, slices) <- assignments
                       slice <- slices} yield AddReplica(collection, slice, node)
    Operation(actions)
  }

  /**
   * Using the maximum number of slices per host for the given collection as a maximum, adds
   * as many replicas as possible given the nodes currently in the cluster. Tries to add replicas
   * in order of the fewest replicas for a given slice.
   * May end up with an unequal number of replicas for each slice in the collection, if the number
   * of nodes doesn't divide evenly.
   *
   * @param clusterManager
   * @param collection
   * @return The corresponding Operation
   */
  def fillCluster(clusterManager: ClusterManager, collection: String, nodesOpt: Option[Seq[String]] = None, waitForReplication: Boolean = true): Operation = {
    val state = clusterManager.currentState

    case class Assignment(node: String, slice: String)
    case class Participation(assignments: Seq[Assignment]) {
      lazy val nodeParticipants = assignments.groupBy(_.node).withDefaultValue(Seq())
      lazy val sliceParticipants = assignments.groupBy(_.slice).withDefaultValue(Seq())

      private def participationCounts(p: Map[String, Seq[Assignment]]) =
        p.map{ case (node, nodeAssignments) => (node, nodeAssignments.size) }.withDefaultValue(0)

      lazy val slicesPerNode = participationCounts(nodeParticipants)
      lazy val nodesPerSlice = participationCounts(sliceParticipants)
      def sliceCount(node: String) = slicesPerNode(node)
      def nodeCount(slice: String) = nodesPerSlice(slice)

      def +(newAssignment: Assignment) = Participation(assignments :+ newAssignment)
    }

    assert(state.collections.contains(collection), s"Could find collection $collection")
    val currentReplicas = state.replicasFor(collection)
    val participation = Participation(currentReplicas.map((replica) => Assignment(replica.node, replica.sliceName)))

    // use the node with the most slices as a limiter for how many slices to allow per node
    val maxSlicesPerNode = participation.slicesPerNode.maxBy(_._2)._2
    val currentSlots = nodesOpt.map(nodeList => currentReplicas.filter(replica => nodeList.contains(replica.node))).getOrElse(currentReplicas).size
    val availableNodes = nodesOpt.map(nodeList => state.liveNodes & nodeList.toSet).getOrElse(state.liveNodes)
    val availableSlots = maxSlicesPerNode * availableNodes.size - currentSlots

    @tailrec
    def assignSlot(actions: Seq[AddReplica], participation: Participation, availableSlots: Int): Seq[AddReplica] = {
      if (availableSlots == 0) {
        actions
      }
      else {
        // the slice with the fewest replicas
        val minSlice = participation.nodesPerSlice.minBy(_._2)._1
        val nodesWithoutSlice = availableNodes -- participation.sliceParticipants(minSlice).map(_.node)
        // the node with the fewest replicas that doesn't have the slice with the fewest replicas
        val minNode = nodesWithoutSlice.minBy( participation.sliceCount )

        assignSlot(
          actions :+ AddReplica(collection, minSlice, minNode, waitForReplication),
          participation + Assignment(minNode, minSlice),
          availableSlots - 1)
      }
    }

    Operation(assignSlot(Seq(), participation, availableSlots))
  }

  /**
   * Finds all replicas on a given node, and creates the same set of replicas on another given node.
   * The original node is left untouched, but would be an easy target for a "cleanCluster()" if the
   * intention is a migration.
   * @param clusterManager
   * @param from Node name to gather the replica list from
   * @param onto Node name to add the replicas to
   * @param waitForReplication
   * @return
   */
  def cloneReplicas(clusterManager: ClusterManager, from: String, onto: String, waitForReplication: Boolean = true) = {
    val state = clusterManager.currentState
    val replicasToClone = state.allReplicas.filter(_.node == from)
    Operation(replicasToClone.map(replica => AddReplica(replica.collection, replica.sliceName, onto, waitForReplication)))
  }

  /**
   * Removes any inactive replicas for a given collection.
   * A replica could be "inactive" because it's in a bad state, because the hosting node is down, or because the relevant slice
   * A node need not be up for the replica to be removed.
   * Note that SOLR-6072 means any files on the relevent node are NOT deleted on solr < 4.10.
   * @param clusterManager
   * @param collection
   * @return The corresponding Operation
   */
  def cleanCluster(clusterManager: ClusterManager, collection: String) = {
    val state = clusterManager.currentState

    Operation(
      state.inactiveReplicas.filter(_.collection == collection).map(
        (replica) => DeleteReplica(collection, replica.sliceName, replica.node)
      )
    )
  }

  /**
   * Delete all replicas in all collections from the given node
   * @param clusterManager
   * @param node
   * @return The corresponding Operation
   */
  def wipeNode(clusterManager: ClusterManager, node: String, collectionOpt: Option[String] = None, safetyFactor: Int = 1): Operation = {
    val state = clusterManager.currentState
    val replicasOnNode = collectionOpt.map(collection => state.allReplicas.filter(_.collection == collection))
      .getOrElse(state.allReplicas).filter(_.node == node)

    Operation(replicasOnNode.map( (replica) => DeleteReplica(replica.collection, replica.sliceName, replica.node, safetyFactor)) )
  }

  /**
   * Delete all replicas for a given collection from the given node
   * @param clusterManager
   * @param collection
   * @param node
   * @return The corresponding Operation
   */
  def wipeCollectionFromNode(clusterManager: ClusterManager, collection: String, node: String): Operation = {
    wipeNode(clusterManager, node, Some(collection))
  }

  /**
   * Currently uses coreadmin's fetchindex command even for populating replicas once the leader has been
   * updated. Might be able to do something like this instead:
   * http://10.8.100.42:7575/solr/admin/cores?action=REQUESTRECOVERY&core=collection1_shard1_replica1
   * @param clusterManager
   * @param collection
   * @param deployFrom
   * @return
   */
  def deployFromAnotherCluster(clusterManager: ClusterManager, collection: String, deployFrom: String): Operation = {
    def firstCore(coreName: String) = coreName.replaceAll("""replica\d""", "replica1")

    val state = clusterManager.currentState
    val replicaGroup = state.replicasFor(collection).groupBy(_.sliceName).values.toList.sortBy(_.head.core)
    val operations = for { replicas <- replicaGroup } yield {
      val (leader, copies) = replicas.partition(_.leader)
      Operation(
        leader.flatMap( (r) =>
          FetchIndex(firstCore(r.core), r.core, deployFrom) +: copies.map( (c) => FetchIndex(r.core, c.core, r.host, ""))
        )
      )
    }
    operations.fold(Operation.empty)(_ ++ _)
  }

  /**
   * Requests a backup of the index for a given collection. Only the leader replica for each slice will create a backup.
   *
   * In order for this to function as a reliable backup mechanism, or for the "restoreCollection" operation to work,
   * the dir must be a shared filesystem among all nodes.
   *
   * The collection and slice name will be appended to the backup dir provided. It's assumed that the
   * path separator is the same on this machine and the nodes. "keep" will be be considered per-slice.
   * So, the number of backups of each slice on a given node will be no greater than "keep".
   *
   * This does NOT back up any cluster state stored in Zookeeper, including the collection definition or config.
   * @param clusterManager
   * @param collection
   * @param dir The base directory on the node to save backups in.
   * @param keep The number of backups to keep, per slice, including this one. The new backup will be created before
   *             old ones are cleaned, so you need space for n+1 backups.
   * @param parallel Execute all backup requests without waiting to see if they finish.
   * @return An operation that backs up the given collection.
   */
  def backupCollection(clusterManager: ClusterManager, collection: String,
                       dir: String, keep: Int, parallel: Boolean): Operation = {
    val state = clusterManager.currentState
    val collectionReplicas = state.liveReplicasFor(collection)
    val backupReplicas = collectionReplicas.filter(_.leader)

    // The default Solr backup naming and retention strategy doesn't distinguish backups, so we
    // want to encode more distinguishing information in the directory structure
    def backupDir(replica: SolrReplica) = Paths.get(dir, replica.collection, replica.sliceName).toString

    Operation(backupReplicas.map(r => BackupIndex(r.core, backupDir(r), !parallel, keep)))
  }

  /**
   * Restores all cores in a given collection from the most recent backup made by this tool.
   *
   * Presumes the necessary slices are available on the necessary nodes. In practice, the only way to know this
   * for sure is if the dir is a shared filesystem across all nodes, or if you only have one node in your cluster.
   *
   * Presumes that the collection you're restoring into is mostly identical (schema, shard count, etc) to the
   * one that made the backup, and that the backup was made by this tool. Only the collection name and replication
   * factor should be different.
   * @param clusterManager
   * @param collection The name of the (existing) collection to restore into
   * @param dir The same value used for the backup command
   * @param oldCollection The name of the collection that made the backup, if different.
   * @return An operation that restores the given collection.
   */
  def restoreCollection(clusterManager: ClusterManager, collection: String,
                        dir: String, oldCollection: Option[String]): Operation = {
    val state = clusterManager.currentState
    val collectionReplicas = state.liveReplicasFor(collection)
    def backupDir(replica: SolrReplica) = {
      Paths.get(dir, oldCollection.getOrElse(replica.collection), replica.sliceName).toString
    }

    Operation(collectionReplicas.map(r => RestoreIndex(r.core, backupDir(r))))
  }


}
