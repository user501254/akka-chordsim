package com.ashessin.cs441.project.workers

import akka.actor.SupervisorStrategy.{Restart, Stop}
import akka.actor._

import scala.collection.mutable
import scala.concurrent.duration._

/**
 * The worker is actually more of a middle manager, delegating the actual work
 * to the WorkExecutor, supervising it and keeping itself available to interact with the work master.
 */
object Worker {

  def props(masterProxy: ActorRef,
            workerId: String, workerFinger: mutable.TreeMap[String, (Range, String)], workers: Int): Props = Props(
    new Worker(masterProxy, workerId.toString, workerFinger, workers))

}

class Worker(masterProxy: ActorRef, workerId: String, workerFinger: mutable.TreeMap[String, (Range, String)], workers: Int)
  extends Actor with Timers with ActorLogging {
  import MasterWorkerProtocol._
  import context.dispatcher


  val id: String = workerId
  var finger: mutable.TreeMap[String, (Range, String)] = workerFinger
  log.info(f"[worker-$id], ${self.path}, finger: ${finger}")

  val registerInterval: FiniteDuration = context.system.settings.config.getDuration("distributed-workers.worker-registration-interval").getSeconds.seconds

  val registerTask: Cancellable = context.system.scheduler.schedule(0.seconds, registerInterval, masterProxy, RegisterWorker(workerId))

  val workExecutor: ActorRef = createWorkExecutor()

  var currentWork: Option[(String, Int, ActorRef)] = None
  def work: (String, Int, ActorRef) = currentWork match {
    case Some(work) => work
    case None       => throw new IllegalStateException("Not working")
  }

  def receive: Receive = idle

  def idle: Receive = {
    case WorkIsReady =>
      // this is the only state where we reply to WorkIsReady
      masterProxy ! WorkerRequestsWork(workerId)

    case Work(workId, job: Int, frontEnd) =>
      // TODO: Refactor
      val workIdHash = (workId.toArray.foldLeft(0)(_ + _.toInt) % workers).toString
      if (workerId == workIdHash) {
        log.info(f"[worker-$workerId] Got job: $job with workId: $workId, workIdHash: $workIdHash")
        currentWork = Some(workId, job, frontEnd)
        workExecutor ! WorkExecutor.DoWork(job, frontEnd)
        context.become(working)
        masterProxy ! WorkerForwardsWork(workerId, workId, forward = false)
      } else {
        val successor = getSuccessor(workIdHash, finger)._2
        log.info(f"[worker-$workerId] Forward job: $job with workId: $workId, workIdHash: $workIdHash to successor: $successor")
        masterProxy ! WorkerForwardsWork(successor, workId, forward = true)
      }
  }

  def working: Receive = {
    case WorkExecutor.WorkComplete(result, job, actorRef) =>
      log.info(f"[worker-$workerId] Completed work: $work with result: $result")
      masterProxy ! WorkIsDone(workerId, work._1, result, job, actorRef)
      context.setReceiveTimeout(5.seconds)
      context.become(waitForWorkIsDoneAck(result, job, actorRef))

    case _: Work =>
      log.warning(f"[worker-$workerId] Yikes. Master told me to do work on work: $work, but currentWork: $currentWork")

  }

  def waitForWorkIsDoneAck(result: Any, job: Int, actorRef: ActorRef): Receive = {
    case Ack(id) if id == work._1 =>
      masterProxy ! WorkerRequestsWork(workerId)
      context.setReceiveTimeout(Duration.Undefined)
      context.become(idle)

    case ReceiveTimeout =>
      log.info(f"[worker-$workerId] No ack from master, resending workId: $work with result: $result")
      masterProxy ! WorkIsDone(workerId, work._1, result, job, actorRef)

  }

  def createWorkExecutor(): ActorRef =
    // in addition to starting the actor we also watch it, so that
    // if it stops this worker will also be stopped
    context.watch(context.actorOf(WorkExecutor.props, "work-executor"))

  override def supervisorStrategy: OneForOneStrategy = OneForOneStrategy() {
    case _: ActorInitializationException => Stop
    case _: Exception =>
      currentWork foreach { work => masterProxy ! WorkFailed(workerId, work. _1, work._2, work._3)}
      context.become(idle)
      Restart
  }

  override def postStop(): Unit = {
    registerTask.cancel()
    masterProxy ! DeRegisterWorker(workerId)
  }

//  def lookupSuccessor(id: String, finger: mutable.TreeMap[String, (Range, String)]): (Range, String) = {
//    println("lookupSuccessor-1")
//    (1 to finger.size).foreach(i => {
//      val position = i.toString
//      if (finger(position)._2 == id) {
//        return finger(position)
//      }
//    })
//    println("lookupSuccessor-2")
//    lookupClosestPredecessor(id, finger)
//  }
//
//  def lookupClosestPredecessor(id: String, finger: mutable.TreeMap[String, (Range, String)]): (Range, String) = {
//    println("lookupClosestPredecessor-1")
//    (1 to finger.size).foreach(i => {
//      val position = i.toString
//      if (finger(position)._1 contains id.toInt) {
//        return finger(position)
//      }
//    })
//
//    println("lookupClosestPredecessor-2")
//    finger.last._2
//  }

  def getSuccessor(toFind: String, fingerTable: mutable.TreeMap[String, (Range, String)]): (Range, String) = {
    //create hashmap with key as i and value as absolute difference
    val absDiff = fingerTable.map(x => {
      (x._1, (x._2._2.toInt - toFind.toInt).abs)
    })

    //get the key of the minimum difference
    val minDiffKey : String = absDiff.minBy(_._2)._1

    //Check if the to Find is less than the current node
    //if yes return the max successor
    if(toFind.toInt < absDiff.minBy(_._2)._2)
      fingerTable.maxBy(_._2._2.toInt)._2
    //else return the successor at min distance
    else
      fingerTable(minDiffKey)
  }


}