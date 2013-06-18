package spark.deploy.master

import akka.actor.{ActorRef}
import akka.dispatch.Await
import akka.pattern.ask
import akka.util.{Duration}
import akka.util.duration._
import scala.xml.Node
import spark.{Logging, Utils}
import spark.util.{WebUI => UtilsWebUI}

import spark.deploy._
import org.eclipse.jetty.server.Handler
import spark.util.WebUI._
import spark.deploy.MasterState
import javax.servlet.http.HttpServletRequest

/**
 * Web UI server for the standalone master.
 */
private[spark]
class MasterWebUI(master: ActorRef) extends Logging {

  implicit val timeout = Duration.create(System.getProperty("spark.akka.askTimeout", "10").toLong, "seconds")
  val host = Utils.localHostName()
  val port = Option(System.getProperty("spark.ui.port"))
    .getOrElse(MasterWebUI.DEFAULT_PORT).toInt

  def start() {
    try {
      val (server, boundPort) = UtilsWebUI.startJettyServer("0.0.0.0", port, handlers)
      logInfo("Started Master web UI at http://%s:%d".format(host, boundPort))
    } catch {
      case e: Exception =>
        logError("Failed to create Master WebUI", e)
        System.exit(1)
    }
  }

  val handlers = Array[(String, Handler)](
    ("/static", createStaticHandler(MasterWebUI.STATIC_RESOURCE_DIR)),
    ("/app", (request: HttpServletRequest) => appDetail(request)),
    ("*", (request: HttpServletRequest) => index)
  )

  /** Executor details for a particular application */
  def appDetail(request: HttpServletRequest): Seq[Node] = {
    val appId = request.getParameter("appId")
    val stateFuture = (master ? RequestMasterState)(timeout).mapTo[MasterState]
    val state = Await.result(stateFuture, 3 seconds)
    val app = state.activeApps.find(_.id == appId).getOrElse({
      state.completedApps.find(_.id == appId).getOrElse(null)
    })
    val content =
      <div class="row">
        <div class="span12">
          <ul class="unstyled">
            <li><strong>ID:</strong> app.id</li>
            <li><strong>Description:</strong> app.desc.name</li>
            <li><strong>User:</strong> app.desc.user</li>
            <li><strong>Cores:</strong>
              {
                if (app.desc.maxCores == Integer.MAX_VALUE) {
                  "Unlimited %s granted".format(app.coresGranted)
                } else {
                  "%s (%s granted, %s left)".format(
                  app.desc.maxCores, app.coresGranted, app.coresLeft)
               }
             }
            </li>
            <li><strong>Memory per Slave:</strong> {app.desc.memoryPerSlave}</li>
            <li><strong>Submit Date:</strong> {app.submitDate}</li>
            <li><strong>State:</strong> {app.state}</li>
            <li><strong><a href={app.appUiUrl}>Application Detail UI</a></strong></li>
          </ul>
        </div>
      </div>

      <hr/>

      <div class="row"> <!-- Executors -->
        <div class="span12">
          <h3> Executor Summary </h3>
          <br/>
          {executorsTable(app.executors.values.toList)}
        </div>
      </div>;
      UtilsWebUI.makePage(content, "Application Info: " + app.desc.name)
  }

  def executorsTable(executors: Seq[ExecutorInfo]): Seq[Node] = {
    <table class="table table-bordered table-striped table-condensed">
      <thead>
        <tr>
          <th>ExecutorID</th>
          <th>Worker</th>
          <th>Cores</th>
          <th>Memory</th>
          <th>State</th>
          <th>Logs</th>
        </tr>
      </thead>
      <tbody>
        {executors.map(executorRow)}
    </tbody>
    </table>
  }

  def executorRow(executor: ExecutorInfo): Seq[Node] = {
    <tr>
      <td>{executor.id}</td>
      <td>
        <a href={executor.worker.webUiAddress}>{executor.worker.id}</a>
      </td>
      <td>{executor.cores}</td>
      <td>{executor.memory}</td>
      <td>{executor.state}</td>
      <td>
        <a href={"%s/log?appId=%s&executorId=%s&logType=stdout"
            .format(executor.worker.webUiAddress, executor.application.id, executor.id)}>stdout</a>
        <a href={"%s/log?appId=%s&executorId=%s&logType=stderr"
          .format(executor.worker.webUiAddress, executor.application.id, executor.id)}>stdout</a>
      </td>
    </tr>
  }

  /** Index view listing applications and executors */
  def index: Seq[Node] = {
    val stateFuture = (master ? RequestMasterState)(timeout).mapTo[MasterState]
    val state = Await.result(stateFuture, 3 seconds)

    val content =
      <div class="row">
        <div class="span12">
          <ul class="unstyled">
            <li><strong>URL:</strong>{state.uri}</li>
            <li><strong>Workers:</strong>{state.workers.size}</li>
            <li><strong>Cores:</strong> {state.workers.map(_.cores).sum}Total,
              {state.workers.map(_.coresUsed).sum} Used</li>
            <li><strong>Memory:</strong>
              {Utils.memoryMegabytesToString(state.workers.map(_.memory).sum)} Total,
              {Utils.memoryMegabytesToString(state.workers.map(_.memoryUsed).sum)} Used</li>
            <li><strong>Applications:</strong>
              {state.activeApps.size} Running,
              {state.completedApps.size} Completed </li>
          </ul>
        </div>
      </div>

      <div class="row">
        <div class="span12">
          <h3> Workers </h3>
          <br/>
          {workerTable(state.workers.sortBy(_.id))}
        </div>
      </div>

        <hr/>

      <div class="row">
        <div class="span12">
          <h3> Running Applications </h3>
          <br/>
          {appTable(state.activeApps.sortBy(_.startTime).reverse)}
        </div>
      </div>

        <hr/>

      <div class="row">
        <div class="span12">
          <h3> Completed Applications </h3>
          <br/>
          {appTable(state.completedApps.sortBy(_.endTime).reverse)}
        </div>
      </div>;
    UtilsWebUI.makePage(content, "Spark Master: " + state.uri)
  }

  def workerTable(workers: Seq[spark.deploy.master.WorkerInfo]) = {
    <table class="table table-bordered table-striped table-condensed sortable">
      <thead>
        <tr>
          <th>ID</th>
          <th>Address</th>
          <th>State</th>
          <th>Cores</th>
          <th>Memory</th>
        </tr>
      </thead>
      <tbody>
        {
          workers.map{ worker =>
            <tr>
              <td>
                <a href={worker.webUiAddress}>{worker.id}</a>
              </td>
              <td>{worker.host}:{worker.port}</td>
              <td>{worker.state}</td>
              <td>{worker.cores} ({worker.coresUsed} Used)</td>
              <td>{Utils.memoryMegabytesToString(worker.memory)}
                ({Utils.memoryMegabytesToString(worker.memoryUsed)} Used)</td>
            </tr>
          }
        }
    </tbody>
    </table>
  }

  def appTable(apps: Seq[spark.deploy.master.ApplicationInfo]) = {
    <table class="table table-bordered table-striped table-condensed sortable">
      <thead>
        <tr>
          <th>ID</th>
          <th>Description</th>
          <th>Cores</th>
          <th>Memory per Node</th>
          <th>Submit Time</th>
          <th>User</th>
          <th>State</th>
          <th>Duration</th>
        </tr>
      </thead>
      <tbody>
        {
          apps.map{ app =>
            <tr>
              <td>
                <a href={"app?appId=" + app.id}>{app.id}</a>
              </td>
              <td>{app.desc.name}</td>
              <td>
                {app.coresGranted}
              </td>
              <td>{Utils.memoryMegabytesToString(app.desc.memoryPerSlave)}</td>
              <td>{WebUI.formatDate(app.submitDate)}</td>
              <td>{app.desc.user}</td>
              <td>{app.state.toString}</td>
              <td>{WebUI.formatDuration(app.duration)}</td>
            </tr>
          }
        }
    </tbody>
    </table>
  }
}

object MasterWebUI {
  val STATIC_RESOURCE_DIR = "spark/deploy/static"
  val DEFAULT_PORT = "34000"
}