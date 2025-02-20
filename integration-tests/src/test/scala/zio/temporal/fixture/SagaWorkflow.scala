package zio.temporal.fixture

import zio._
import zio.temporal._
import zio.temporal.activity._
import zio.temporal.workflow._

case class TransferError(msg: String) extends Exception(msg)
case class Done()

@activityInterface
trait TransferActivity {
  @throws[TransferError]
  def deposit(account: String, amount: BigDecimal): Done

  @throws[TransferError]
  def withdraw(account: String, amount: BigDecimal): Done
}

class TransferActivityImpl(
  depositFunc:      (String, BigDecimal) => IO[TransferError, Done],
  withdrawFunc:     (String, BigDecimal) => IO[TransferError, Done]
)(implicit options: ZActivityOptions[Any])
    extends TransferActivity {

  override def deposit(account: String, amount: BigDecimal): Done = {
    ZActivity.run {
      depositFunc(account, amount)
    }
  }

  override def withdraw(account: String, amount: BigDecimal): Done =
    ZActivity.run {
      withdrawFunc(account, amount)
    }
}

case class TransferCommand(from: String, to: String, amount: BigDecimal)

@workflowInterface
trait SagaWorkflow {

  @workflowMethod
  def transfer(command: TransferCommand): BigDecimal
}

class SagaWorkflowImpl extends SagaWorkflow {

  private val activity = ZWorkflow
    .newActivityStub[TransferActivity]
    .withStartToCloseTimeout(5.seconds)
    .withRetryOptions(
      ZRetryOptions.default
        .withMaximumAttempts(1)
        .withDoNotRetry(nameOf[TransferError])
    )
    .build

  override def transfer(command: TransferCommand): BigDecimal = {
    val saga = for {
      _ <- ZSaga.attempt(
             ZActivityStub.execute(
               activity.withdraw(command.from, command.amount)
             )
           )
      _ <- ZSaga.make(
             ZActivityStub.execute(
               activity.deposit(command.to, command.amount)
             )
           )(compensate = ZActivityStub.execute(activity.deposit(command.from, command.amount)))
    } yield command.amount

    saga.runOrThrow()
  }
}
