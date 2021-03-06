package com.karasiq.shadowcloud.webapp.components.region

import scala.concurrent.Future

import akka.util.ByteString
import rx._
import rx.async._

import com.karasiq.bootstrap.Bootstrap.default._
import scalaTags.all._

import com.karasiq.shadowcloud.config.SerializedProps
import com.karasiq.shadowcloud.model.{RegionId, StorageId}
import com.karasiq.shadowcloud.model.utils.{GCReport, RegionHealth, SyncReport}
import com.karasiq.shadowcloud.model.utils.RegionStateReport.RegionStatus
import com.karasiq.shadowcloud.webapp.components.common.{AppComponents, AppIcons}
import com.karasiq.shadowcloud.webapp.context.AppContext
import com.karasiq.shadowcloud.webapp.context.AppContext.JsExecutionContext

object RegionConfigView {
  def apply(regionId: RegionId)(implicit context: AppContext, regionContext: RegionContext): RegionConfigView = {
    new RegionConfigView(regionId)
  }

  private def toRegionConfig(regionStatus: RegionStatus, newConfig: String): SerializedProps = {
    val format = Option(regionStatus.regionConfig.format)
      .filter(_.nonEmpty)
      .getOrElse(SerializedProps.DefaultFormat)

    SerializedProps(format, ByteString(newConfig))
  }
}

class RegionConfigView(regionId: RegionId)(implicit context: AppContext, regionContext: RegionContext) extends BootstrapHtmlComponent {
  private[this] lazy val regionStatus = regionContext.region(regionId)

  private[this] lazy val compactStarted = Var(false)
  private[this] lazy val gcStarted = Var(false)
  private[this] lazy val repairStarted = Var(false)

  def renderTag(md: ModifierT*): TagT = {
    div(regionStatus.map { regionStatus ⇒
      div(
        if (!regionStatus.suspended) {
          Seq(
            renderRegionHealth(),
            renderCompactButton(),
            renderGCButton(),
            renderRepairButton(),
            hr
          )
        } else (),
        renderStateButtons(regionStatus),
        renderConfigField(regionStatus),
        hr,
        renderStoragesRegistration(regionStatus)
      )
    })
  }

  private[this] def renderRegionHealth() = {
    val regionHealth = context.api.getRegionHealth(regionId).toRx(RegionHealth.empty)
    HealthView(regionHealth)
  }

  private[this] def renderGCButton() = {
    val gcAnalysed = Var(false)
    val gcReport = Var(None: Option[GCReport])

    def startGC(delete: Boolean) = {
      gcStarted() = true
      val future = context.api.collectGarbage(regionId, delete)
      future.onComplete(_ ⇒ gcStarted() = false)
      future.foreach { report ⇒
        gcAnalysed() = !delete
        gcReport() = Some(report)
      }
    }

    val gcBlocked = Rx(gcStarted() || compactStarted() || repairStarted())
    div(
      Rx {
        val buttonStyle = if (gcAnalysed()) ButtonStyle.danger else ButtonStyle.warning
        Button(buttonStyle, ButtonSize.extraSmall)(
          AppIcons.delete, context.locale.collectGarbage, "disabled".classIf(gcBlocked),
          onclick := Callback.onClick(_ ⇒ if (!gcBlocked.now) startGC(delete = gcAnalysed.now))
        )
      },
      div(gcReport.map[Frag] {
        case Some(report) ⇒ div(Alert(AlertStyle.warning, report.toString))
        case None ⇒ Bootstrap.noContent
      })
    )
  }

  private[this] def renderCompactButton() = {
    val compactReport = Var(Map.empty[StorageId, SyncReport])

    def startCompact() = {
      compactStarted() = true
      val future = context.api.compactIndexes(regionId)
      future.onComplete(_ ⇒ compactStarted() = false)
      future.foreach(compactReport() = _)
    }

    def renderReports(reports: Map[StorageId, SyncReport]): Frag = {
      if (reports.nonEmpty)
        div(Alert(AlertStyle.success, for ((storageId, report) ← reports.toSeq) yield div(b(storageId), ": ", report.toString)))
      else
        Bootstrap.noContent
    }

    div(
      Button(ButtonStyle.danger, ButtonSize.extraSmall)(AppIcons.compress, context.locale.compactIndex, "disabled".classIf(compactStarted),
        onclick := Callback.onClick(_ ⇒ if (!compactStarted.now) startCompact())),
      div(compactReport.map(renderReports))
    )
  }

  def renderRepairButton(): TagT = {
    def showDialog(): Unit = {
      def doRepair(storages: Seq[StorageId]) = {
        if (!repairStarted.now) {
          repairStarted() = true
          val future = context.api.repairRegion(regionId, storages)
          future.onComplete(_ ⇒ repairStarted() = false)
        }
      }

      context.api.getRegion(regionId).foreach { status ⇒
        val storagesSelector = FormInput.simpleMultipleSelect(context.locale.storages, status.storages.toSeq:_*)
        Modal(context.locale.repairFile)
          .withBody(Form(storagesSelector))
          .withButtons(
            AppComponents.modalSubmit(onclick := Callback.onClick(_ ⇒ doRepair(storagesSelector.selected.now))),
            AppComponents.modalClose()
          )
          .show()
      }
    }

    Button(ButtonStyle.success, ButtonSize.extraSmall)(AppIcons.repair, context.locale.repairRegion, "disabled".classIf(repairStarted),
      onclick := Callback.onClick(_ ⇒ if (!repairStarted.now) showDialog()))
  }

  private[this] def renderStateButtons(regionStatus: RegionStatus) = {
    def doSuspend() = context.api.suspendRegion(regionId).foreach(_ ⇒ regionContext.updateRegion(regionId))
    def doResume() = context.api.resumeRegion(regionId).foreach(_ ⇒ regionContext.updateRegion(regionId))
    def doDelete() = context.api.deleteRegion(regionId).foreach(_ ⇒ regionContext.updateAll())

    val suspendResumeButton = if (regionStatus.suspended)
      Button(ButtonStyle.success, ButtonSize.extraSmall)(AppIcons.resume, context.locale.resume, onclick := Callback.onClick(_ ⇒ doResume()))
    else
      Button(ButtonStyle.warning, ButtonSize.extraSmall)(AppIcons.suspend, context.locale.suspend, onclick := Callback.onClick(_ ⇒ doSuspend()))

    val deleteButton = Button(ButtonStyle.danger, ButtonSize.extraSmall)(AppIcons.delete, context.locale.delete, onclick := Callback.onClick(_ ⇒ doDelete()))

    ButtonGroup(ButtonGroupSize.extraSmall, suspendResumeButton, deleteButton)
  }

  private[this] def renderConfigField(regionStatus: RegionStatus) = {
    val defaultConfig =
      """storage-selector="com.karasiq.shadowcloud.storage.replication.selectors.SimpleStorageSelector"
        |data-replication-factor=1
        |index-replication-factor=0
        |garbage-collector {
        |    auto-delete=false
        |    keep-file-revisions=5
        |    keep-recent-files="30d"
        |    run-on-low-space="100M"
        |}
      """.stripMargin

    def renderConfigForm() = {
      val changed = Var(false)
      val newConfigRx = Var(regionStatus.regionConfig.data.utf8String)
      newConfigRx.triggerLater(changed() = true)
      
      Form(
        FormInput.textArea((), rows := 10, newConfigRx.reactiveInput, placeholder := defaultConfig, AppComponents.tabOverride),
        Form.submit(context.locale.submit, changed.reactiveShow),
        onsubmit := Callback.onSubmit { _ ⇒
          val newConfig = RegionConfigView.toRegionConfig(regionStatus, newConfigRx.now)
          context.api.createRegion(regionId, newConfig).foreach(_ ⇒ regionContext.updateRegion(regionId))
        }
      )
    }

    AppComponents.dropdown(context.locale.config)(renderConfigForm())
  }

  private[this] def renderStoragesRegistration(regionStatus: RegionStatus) = {
    def updateStorageList(newIdSet: Set[StorageId]) = {
      val currentIdSet = regionStatus.storages
      val toRegister = newIdSet -- currentIdSet
      val toUnregister = currentIdSet -- newIdSet
      for {
        _ ← Future.sequence(toUnregister.map(context.api.unregisterStorage(regionId, _)))
        _ ← Future.sequence(toRegister.map(context.api.registerStorage(regionId, _)))
      } regionContext.updateAll()
    }

    def renderAddButton() = {
      def showAddDialog(): Unit = {
        val allIds = regionContext.regions.now.storages.keys.toSeq.sorted
        val idSelect = FormInput.multipleSelect(context.locale.storages, allIds.map(id ⇒ FormSelectOption(id, id)))
        idSelect.selected() = regionStatus.storages.toSeq
        Modal()
          .withTitle(context.locale.registerStorage)
          .withBody(Form(idSelect))
          .withButtons(
            AppComponents.modalSubmit(onclick := Callback.onClick(_ ⇒ updateStorageList(idSelect.selected.now.toSet))),
            AppComponents.modalClose()
          )
          .show()
      }

      Button(ButtonStyle.primary, ButtonSize.extraSmall)(AppIcons.register, context.locale.registerStorage, onclick := Callback.onClick(_ ⇒ showAddDialog()))
    }

    def renderStorage(storageId: StorageId) = {
      div(
        b(storageId),
        Bootstrap.textStyle.success
      )
    }

    val storagesSeq = regionStatus.storages.toSeq.sorted
    div(
      storagesSeq.map(renderStorage),
      renderAddButton()
    )
  }
}

