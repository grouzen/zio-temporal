package zio.temporal.activity

import io.temporal.activity.*
import zio.temporal.internal.ClassTagUtils
import scala.reflect.ClassTag
import scala.jdk.OptionConverters._
class ZActivityExecutionContext private[zio] (val toJava: ActivityExecutionContext) extends AnyVal {

  /** Used to notify the Workflow Execution that the Activity Execution is alive.
    *
    * @param details
    *   In case the Activity Execution times out details are returned as a field of the exception that is thrown. The
    *   details are also accessible through [[getHeartbeatDetails]] on the next Activity Execution retry.
    * @throws ActivityCompletionException
    *   Which indicates that cancellation of the Activity Execution was requested by the Workflow Execution. Or it could
    *   indicate any other reason for an Activity Execution to stop. Should be rethrown from the Activity implementation
    *   to indicate a successful cancellation.
    */
  def heartbeat[V](details: V): Unit =
    toJava.heartbeat(details)

  /** Extracts Heartbeat details from the last failed attempt. This is used in combination with retry options. An
    * Activity Execution could be scheduled with optional [[zio.temporal.common.ZRetryOptions]] via
    * [[zio.temporal.activity.ZActivityOptions]]. If an Activity Execution failed then the server would attempt to
    * dispatch another Activity Task to retry the execution according to the retry options. If there were Heartbeat
    * details reported by the last Activity Execution that failed, they would be delivered along with the Activity Task
    * for the next retry attempt and can be extracted by the Activity implementation.
    *
    * @tparam V
    *   type of the Heartbeat details
    */
  def getHeartbeatDetails[V: ClassTag]: Option[V] =
    toJava.getHeartbeatDetails(ClassTagUtils.classOf[V]).toScala
}
