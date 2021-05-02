package SyncUnitTest
import akka.actor.AbstractActor.Receive
import com.{Automata, MessageBundle, MyTransition, SecureActor}
import akka.actor.{ActorSystem, Props}
import akka.testkit.{DefaultTimeout, ImplicitSender, TestActorRef, TestKit, TestProbe}
import com.SecureActor.{AskControlMessage, TellControlMessage}
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._


class SecureActorTest
  extends TestKit(ActorSystem("SendNotifTest", ConfigFactory.parseString(TestKitUsageSpec.config)))
  with DefaultTimeout
  with ImplicitSender
  with AnyWordSpecLike
  with Matchers
  with BeforeAndAfterAll{

  "handle pending" should {
    "remove pending messages properly from pending dataset" in {
      val customBundle: MessageBundle = new MessageBundle("secondActor", "a0", "firstActor")
      val customTransition: MyTransition = MyTransition(0,1, customBundle ,true)
      val customBundle2: MessageBundle = new MessageBundle( "firstActor" , "b1","secondActor")
      val customTransition2: MyTransition = MyTransition(1, 2, customBundle2, true)
      val actorRefB: TestActorRef[SecureActor] = TestActorRef(SecureActor.props().withDispatcher("custom-dispatcher"), "secondActor")
      val actorA = TestProbe()
      val actorB = actorRefB.underlyingActor
      val askMsg = AskControlMessage(customTransition2, actorA.ref, actorB.vectorClock, customTransition, false)
      actorB.pendingMonitorMessage = actorB.pendingMonitorMessage :+ askMsg
      assert(actorB.pendingMonitorMessage.length == 1)
      actorB.history = actorB.history :+ (customTransition, actorB.vectorClock, "?")
      actorB.handlePending(customTransition)
      assert(actorB.pendingMonitorMessage.length == 0)
      actorA.expectMsgPF(){
        case TellControlMessage(message, teller, dest, vc, msgVC, inspectedTransition, repRecs) => assert(repRecs(0)._3 == "frmP")
      }
    }
  }
}

object TestKitUsageSpec {
  // Define your test specific configuration here
  val config = """
    akka {
      loglevel = "WARNING"
    }
    custom-dispatcher {
      mailbox-requirement =
        "com.MyControlAwareMessageQueueSemantics"
    }
    akka.actor.mailbox.requirements {
      "com.MyControlAwareMessageQueueSemantics" =
        custom-dispatcher-mailbox
    }

    custom-dispatcher-mailbox {
      mailbox-type = "com.MyControlAwareMailbox"
    }
    """


}