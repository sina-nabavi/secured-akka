/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package com

import akka.actor.ActorRef

import scala.collection.mutable

//import java.util.Queue
//import java.util.concurrent.ConcurrentLinkedQueue

//import akka.dispatch.{ UnboundedControlAwareMessageQueueSemantics, _ }
//import com.typesafe.config.Config

//MyNote
//the code here is pretty simple and straightforward
//use this.init() for hard coding the initiation.

class Automata {
  var transitions: Vector[MyTransition] = Vector.empty[MyTransition]
  var lastTransitions: Vector[Int] = Vector.empty[Int]

  def addTransition(newTransition: MyTransition): Unit =
  {
    transitions = transitions :+ newTransition
  }

  def addLastTransition(lastTran: Int): Unit =
  {
    lastTransitions = lastTransitions :+ lastTran
  }

  def findPre(inputTransitions: Vector[MyTransition]): Vector[MyTransition] =
  {
    var returnVar: Vector[MyTransition] = Vector.empty[MyTransition]
    for (inputTransition ← inputTransitions)
      for (transition ← transitions) {
        if (transition.to == inputTransition.from)
          returnVar = returnVar :+ transition
      }
    returnVar
  }

  def getAllVioTransitions(): Vector[MyTransition] =
  {
    var returnVar: Vector[MyTransition] = Vector.empty[MyTransition]
    for(transition <- transitions) {
      if(transition.regTransition == false)
        returnVar = returnVar :+ transition
    }
    returnVar
  }

  def isInPath(inspected: MyTransition, vioTransitions: Vector[MyTransition]): Vector[MyTransition] = {
    // own vertices transition
    // in this func check for all the vio transitions
      var pendingPreTrans: mutable.Set[MyTransition] = mutable.Set.empty[MyTransition]
      var pendingPostTrans: mutable.Set[MyTransition] = mutable.Set.empty[MyTransition]
      var preTrans: mutable.Set[MyTransition] = mutable.Set.empty[MyTransition]
      var postTrans: mutable.Set[MyTransition] = mutable.Set.empty[MyTransition]
      var pres: mutable.Set[Int] = mutable.Set.empty[Int]
      var posts: mutable.Set[Int] = mutable.Set.empty[Int]
      pres += inspected.from
      posts += inspected.to
      pendingPreTrans ++= singleFindPre(inspected)
      val lastPreTrans : MyTransition = pendingPreTrans.last
      while(!pendingPreTrans.isEmpty){
        if(preTrans.contains(lastPreTrans)){
          pendingPreTrans -= lastPreTrans
        }
        else{
          preTrans += lastPreTrans
          pendingPreTrans -=lastPreTrans
          pendingPreTrans ++= singleFindPre(lastPreTrans)
          pres+= lastPreTrans.from
          pres+=lastPreTrans.to
        }
      }

    pendingPostTrans ++= singleFindPost(inspected)
    val lastPostTrans : MyTransition = pendingPostTrans.last
    while(!pendingPostTrans.isEmpty){
      if(postTrans.contains(lastPostTrans)){
        pendingPostTrans -= lastPostTrans
      }
      else{
        postTrans += lastPostTrans
        pendingPostTrans -=lastPostTrans
        pendingPostTrans ++= singleFindPost(lastPostTrans)
        posts+= lastPostTrans.from
        posts+=lastPostTrans.to
      }
    }
    var returnVar: Vector[MyTransition] = Vector.empty[MyTransition]
    for(vio <- vioTransitions) {
      if (posts.contains(vio.from) && pres.contains(vio.to))
        returnVar = returnVar :+ vio
    }
    returnVar
  }

  def singleFindVio(inputTransition: MyTransition): Vector[MyTransition] = {
    val vioTransitions = getAllVioTransitions()
    return isInPath(vioTransitions)
  }

  def singleFindPre(inputTransition: MyTransition): Vector[MyTransition] =
  {
    var returnVar: Vector[MyTransition] = Vector.empty[MyTransition]
    for (transition ← transitions) {
      if (transition.to == inputTransition.from)
        returnVar = returnVar :+ transition
    }
    returnVar
  }

  def singleFindPost(inputTransition: MyTransition): Vector[MyTransition] =
  {
    var returnVar: Vector[MyTransition] = Vector.empty[MyTransition]
    for (transition ← transitions) {
      if (transition.from == inputTransition.to)
        returnVar = returnVar :+ transition
    }
    returnVar
  }

  def findTransitionByMessageBundle(messageBundle: MessageBundle): Vector[MyTransition] = //returns vector[Transition] of messageBundles, null in case of not found
  {
    var returnVar: Vector[MyTransition] = Vector.empty[MyTransition]
    for (transition ← transitions)
      if (transition.messageBundle.s == messageBundle.s && transition.messageBundle.r == messageBundle.r && transition.messageBundle.m == messageBundle.m)
        returnVar = returnVar :+ transition
    returnVar
  }

  def isLastTransition(inputTransition: MyTransition): Boolean = //returns true if this is one of the lsat transitions, false otherwise
  {
    if (lastTransitions.isEmpty)
      return false
    if (lastTransitions.contains(inputTransition.to))
      return true
    return false
  }

  def init(): Unit =
  {
    //MyNote
    //use addTransition and addLastTransition to initiate the transitions according to algorithm
  }

}

class MessageBundle(sender: ActorRef, message: Any, receiver: ActorRef) {
  val s=sender
  val m=message
  val r=receiver
}

case class MyTransition(_from: Int, _to: Int, _messageBundle: MessageBundle, _regTransition: Boolean) {

  val from = _from
  val to = _to
  val messageBundle = _messageBundle
  val regTransition = _regTransition
  //MyNote
  //if true, this is regular transition, else, negative transition
}

//MyNote
//TODO shayad bayad ye chizi be in traite ezafe konim, agi kar nakard
//TODO is "private[akka]" right????
//TODO you probably should test it again, so much changes since the last time this was tested.