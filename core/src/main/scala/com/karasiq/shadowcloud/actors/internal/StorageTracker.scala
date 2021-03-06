package com.karasiq.shadowcloud.actors.internal

import scala.collection.mutable
import scala.language.postfixOps

import akka.actor.{ActorContext, ActorRef}

import com.karasiq.shadowcloud.ShadowCloud
import com.karasiq.shadowcloud.model.StorageId
import com.karasiq.shadowcloud.model.utils.StorageHealth
import com.karasiq.shadowcloud.storage.props.StorageProps
import com.karasiq.shadowcloud.storage.replication.RegionStorageProvider
import com.karasiq.shadowcloud.storage.replication.RegionStorageProvider.RegionStorage

private[actors] object StorageTracker {
  def apply()(implicit context: ActorContext): StorageTracker = {
    new StorageTracker()
  }
}

private[actors] final class StorageTracker(implicit context: ActorContext) extends RegionStorageProvider {

  // -----------------------------------------------------------------------
  // State
  // -----------------------------------------------------------------------
  private[this] val sc = ShadowCloud()
  private[this] val storagesById = mutable.AnyRefMap[String, RegionStorage]()
  private[this] val storagesByAR = mutable.AnyRefMap[ActorRef, RegionStorage]()

  // -----------------------------------------------------------------------
  // Contains
  // -----------------------------------------------------------------------
  def contains(dispatcher: ActorRef): Boolean = {
    storagesByAR.contains(dispatcher)
  }

  def contains(storageId: StorageId): Boolean = {
    storagesById.contains(storageId)
  }

  // -----------------------------------------------------------------------
  // Register/unregister
  // -----------------------------------------------------------------------
  def register(storageId: StorageId, props: StorageProps, dispatcher: ActorRef, health: StorageHealth): Unit = {
    context.watch(dispatcher)
    val storage = RegionStorage(storageId, props, sc.configs.storageConfig(storageId, props), dispatcher, health)
    storagesById += storageId → storage
    storagesByAR += dispatcher → storage
    sc.eventStreams.storage.subscribe(context.self, storageId)
  }

  def unregister(storageId: StorageId): Unit = {
    storagesById.remove(storageId).foreach { storage ⇒
      storagesByAR -= storage.dispatcher
      context.unwatch(storage.dispatcher)
    }
    sc.eventStreams.storage.unsubscribe(context.self, storageId)
  }

  def unregister(dispatcher: ActorRef): Unit = {
    storagesByAR.get(dispatcher).foreach(storage ⇒ unregister(storage.id))
  }

  // -----------------------------------------------------------------------
  // Get storages
  // -----------------------------------------------------------------------
  def storages: Seq[RegionStorage] = {
    storagesById.values.toVector
  }

  override def getStorage(storageId: StorageId): RegionStorage = {
    storagesById(storageId)
  }

  def getStorageId(dispatcher: ActorRef): String = {
    storagesByAR(dispatcher).id
  }

  def getDispatcher(storageId: StorageId): ActorRef = {
    storagesById(storageId).dispatcher
  }

  // -----------------------------------------------------------------------
  // Update state
  // -----------------------------------------------------------------------
  def updateHealth(storageId: StorageId, health: StorageHealth): Unit = {
    storagesById.get(storageId).foreach { storage ⇒
      val newStatus = storage.copy(health = health)
      storagesById += storageId → newStatus
      storagesByAR += storage.dispatcher → newStatus
    }
  }
}
