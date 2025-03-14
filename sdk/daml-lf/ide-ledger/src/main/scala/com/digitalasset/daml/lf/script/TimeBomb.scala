// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.daml.lf
package script

import java.util.{Timer, TimerTask}
import scala.concurrent.duration._

private[script] class TimeBomb(delayInMillis: Long) {
  @volatile
  private[this] var exploded: Boolean = false

  def start(): () => Boolean = {
    val task = new TimerTask { override def run(): Unit = exploded = true }
    TimeBomb.timer.schedule(task, delayInMillis)
    hasExploded
  }

  val hasExploded: () => Boolean = () => exploded
}

private[script] object TimeBomb {
  private val timer = new Timer(true)
  def apply(delayInMillis: Long): TimeBomb = new TimeBomb(delayInMillis)
  def apply(duration: Duration): TimeBomb = apply(duration.toMillis)
}
