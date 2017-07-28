package com.karasiq.shadowcloud.config

import scala.concurrent.duration.{FiniteDuration, _}
import scala.language.postfixOps

import com.typesafe.config.Config

import com.karasiq.shadowcloud.config.utils.ConfigImplicits

case class GCConfig(rootConfig: Config, runOnLowSpace: Option[Long], autoDelete: Boolean,
                    keepFileRevisions: Int, keepRecentFiles: FiniteDuration) extends WrappedConfig

object GCConfig extends WrappedConfigFactory[GCConfig] with ConfigImplicits {
  def apply(config: Config): GCConfig = {
    GCConfig(
      config,
      config.optional(_.getBytes("run-on-low-space"): Long).filter(_ > 0),
      config.withDefault(false, _.getBoolean("auto-delete")),
      config.withDefault(10, _.getInt("keep-file-revisions")),
      config.withDefault(30 days, _.getFiniteDuration("keep-recent-files"))
    )
  }
}
