/*
 * Copyright 2021 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.play.audit.http.connector

import java.time.Instant

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock.{verify => _, _}
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.scalatest._
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.mockito.MockitoSugar
import play.api.inject.{ApplicationLifecycle, DefaultApplicationLifecycle}
import play.api.libs.json.{JsObject, JsValue, Json}
import uk.gov.hmrc.audit.handler.AuditHandler
import uk.gov.hmrc.audit.serialiser.{AuditSerialiser, AuditSerialiserLike}
import uk.gov.hmrc.audit.{HandlerResult, WireMockUtils}
import uk.gov.hmrc.http.{HeaderCarrier, SessionId}
import uk.gov.hmrc.play.audit.http.config.{AuditingConfig, BaseUri, Consumer}
import uk.gov.hmrc.play.audit.http.connector.AuditResult._
import uk.gov.hmrc.play.audit.model.{DataCall, DataEvent, ExtendedDataEvent, MergedDataEvent}

import scala.concurrent.{ExecutionContext, Future}

case class MyExampleAudit(userType: String, vrn: String)

class AuditConnectorSpec
  extends AnyWordSpecLike
     with Matchers
     with ScalaFutures
     with IntegrationPatience
     with MockitoSugar
     with OneInstancePerTest {

  implicit val ec: ExecutionContext = RunInlineExecutionContext
  implicit val as: ActorSystem      = ActorSystem()
  implicit val m: Materializer      = ActorMaterializer()//required for play 2.6

  private val consumer = Consumer(BaseUri("datastream-base-url", 8080, "http"))
  private val enabledConfig = AuditingConfig(consumer = Some(consumer), enabled = true, auditSource = "the-project-name", auditSentHeaders = false)

  private val mockSimpleDatastreamHandler: AuditHandler = mock[AuditHandler]
  private val mockMergedDatastreamHandler: AuditHandler = mock[AuditHandler]

  private val mockFlumeHandler: AuditHandler = mock[AuditHandler]
  private val mockLoggingHandler: AuditHandler = mock[AuditHandler]

  private def mockConnector(config: AuditingConfig): AuditConnector = new AuditConnector {
    override def auditingConfig: AuditingConfig = config
    override def materializer: Materializer = implicitly
    override def lifecycle: ApplicationLifecycle = new DefaultApplicationLifecycle()
    override lazy val simpleDatastreamHandler: AuditHandler = mockSimpleDatastreamHandler
    override lazy val mergedDatastreamHandler: AuditHandler = mockMergedDatastreamHandler
    override lazy val loggingConnector: AuditHandler = mockLoggingHandler
    override lazy val auditSerialiser: AuditSerialiserLike = AuditSerialiser
  }

  "creating an AuditConnector" should {
    "allow the configuration to be specified" in {
      val testPort = WireMockUtils.availablePort
      val consumer = Consumer(BaseUri("localhost", testPort, "http"))
      val config = AuditingConfig(consumer = Some(consumer), enabled = true, auditSource = "the-project-name", auditSentHeaders = false)
      val connector = new AuditConnector {
        override def auditingConfig: AuditingConfig = config
        override def materializer: Materializer = implicitly
        override def lifecycle: ApplicationLifecycle = new DefaultApplicationLifecycle()
      }
      val dataCall = DataCall(Map(), Map(), Instant.now())

      val wireMock = new WireMockServer(testPort)
      WireMock.configureFor("localhost", testPort)
      wireMock.start()

      WireMock.stubFor(
        post(urlPathEqualTo("/write/audit"))
          .withRequestBody(containing("DATA_EVENT"))
          .willReturn(aResponse().withStatus(204)))

      WireMock.stubFor(
        post(urlPathEqualTo("/write/audit/merged"))
          .withRequestBody(containing("MERGED_DATA_EVENT"))
          .willReturn(aResponse().withStatus(204)))

      connector.sendEvent(DataEvent("test", "DATA_EVENT")).futureValue
      WireMock.verify(1, postRequestedFor(urlPathEqualTo("/write/audit")))
      WireMock.reset()

      connector.sendMergedEvent(MergedDataEvent("test", "MERGED_DATA_EVENT", request = dataCall, response = dataCall)).futureValue
      WireMock.verify(1, postRequestedFor(urlPathEqualTo("/write/audit/merged")))

      wireMock.stop()
    }
  }

  "sendMergedEvent" should {
    "call merged Datastream with event converted to json" in {
      when(mockMergedDatastreamHandler.sendEvent(any[JsValue])(any[ExecutionContext]))
        .thenReturn(Future.successful(HandlerResult.Success))

      val mergedEvent = MergedDataEvent("Test", "Test", "TestEventId",
          DataCall(Map.empty, Map.empty, Instant.now()),
          DataCall(Map.empty, Map.empty, Instant.now()))

      mockConnector(enabledConfig).sendMergedEvent(mergedEvent).futureValue mustBe Success

      verify(mockMergedDatastreamHandler).sendEvent(any[JsValue])(any[ExecutionContext])
      verifyNoInteractions(mockSimpleDatastreamHandler)
      verifyNoInteractions(mockFlumeHandler)
      verifyNoInteractions(mockLoggingHandler)
    }
  }

  "sendEvent" should {
    val event = DataEvent("source", "type")

    "call Datastream with the event converted to json" in {
      when(mockSimpleDatastreamHandler.sendEvent(any[JsValue])(any[ExecutionContext]))
        .thenReturn(Future.successful(HandlerResult.Success))

      mockConnector(enabledConfig).sendEvent(event).futureValue mustBe AuditResult.Success

      verify(mockSimpleDatastreamHandler).sendEvent(any[JsValue])(any[ExecutionContext])
      verifyNoInteractions(mockFlumeHandler)
      verifyNoInteractions(mockLoggingHandler)
    }

    "add tags if not specified" in {
      when(mockSimpleDatastreamHandler.sendEvent(any[JsValue])(any[ExecutionContext]))
        .thenReturn(Future.successful(HandlerResult.Success))
      val headerCarrier = HeaderCarrier(sessionId = Some(SessionId("session-123")))

      mockConnector(enabledConfig).sendEvent(event)(headerCarrier, ec).futureValue mustBe AuditResult.Success

      val captor = ArgumentCaptor.forClass(classOf[JsValue])
      verify(mockSimpleDatastreamHandler).sendEvent(captor.capture())(any[ExecutionContext])
      val tags = (captor.getValue \ "tags").as[JsObject]
      (tags \ "X-Session-ID").as[String] mustBe "session-123"
    }

    "return Disabled if auditing is not enabled" in {
      val disabledConfig = AuditingConfig(
        consumer    = Some(Consumer(BaseUri("datastream-base-url", 8080, "http"))),
        enabled     = false,
        auditSource = "the-project-name",
        auditSentHeaders = false
      )

      mockConnector(disabledConfig).sendEvent(event).futureValue must be(AuditResult.Disabled)

      verifyNoInteractions(mockSimpleDatastreamHandler)
      verifyNoInteractions(mockFlumeHandler)
      verifyNoInteractions(mockLoggingHandler)
    }
  }

  "sendExtendedEvent" should {
    "call Datastream with extended event data converted to json" in {
      when(mockSimpleDatastreamHandler.sendEvent(any[JsValue])(any[ExecutionContext]))
        .thenReturn(Future.successful(HandlerResult.Success))

      val detail = Json.parse( """{"some-event": "value", "some-other-event": "other-value"}""")
      val event: ExtendedDataEvent = ExtendedDataEvent(auditSource = "source", auditType = "type", detail = detail)

      mockConnector(enabledConfig).sendExtendedEvent(event).futureValue mustBe AuditResult.Success

      verify(mockSimpleDatastreamHandler).sendEvent(any[JsValue])(any[ExecutionContext])
      verifyNoInteractions(mockFlumeHandler)
      verifyNoInteractions(mockLoggingHandler)
    }

    "sendExplicitEvent Map[String,String]" should {
      "call Datastream with tags read from headerCarrier" in {
        when(mockSimpleDatastreamHandler.sendEvent(any[JsValue])(any[ExecutionContext]))
          .thenReturn(Future.successful(HandlerResult.Success))

        val headerCarrier = HeaderCarrier(sessionId = Some(SessionId("session-123")), otherHeaders = Seq("path" -> "/a/b/c"))
        mockConnector(enabledConfig).sendExplicitAudit("theAuditType", Map("a" -> "1"))(headerCarrier, ec)

        val captor = ArgumentCaptor.forClass(classOf[JsValue])
        verify(mockSimpleDatastreamHandler).sendEvent(captor.capture())(any[ExecutionContext])
        (captor.getValue \ "auditSource").as[String] mustBe "the-project-name"
        val tags = (captor.getValue \ "tags").as[JsObject]
        (tags \ "X-Session-ID").as[String] mustBe "session-123"
        (tags \ "path").as[String] mustBe "/a/b/c"
        (captor.getValue \ "detail").as[Map[String,String]] mustBe Map("a" -> "1")
      }
    }

    "sendExplicitEvent [T]" should {
      "call Datastream with tags read from headerCarrier and serialize T" in {
        when(mockSimpleDatastreamHandler.sendEvent(any[JsValue])(any[ExecutionContext]))
          .thenReturn(Future.successful(HandlerResult.Success))

        val writes = Json.writes[MyExampleAudit]

        val headerCarrier = HeaderCarrier(sessionId = Some(SessionId("session-123")), otherHeaders = Seq("path" -> "/a/b/c"))
        mockConnector(enabledConfig).sendExplicitAudit("theAuditType", MyExampleAudit("Agent","123"))(headerCarrier, ec, writes)

        val captor = ArgumentCaptor.forClass(classOf[JsValue])
        verify(mockSimpleDatastreamHandler).sendEvent(captor.capture())(any[ExecutionContext])
        (captor.getValue \ "auditSource").as[String] mustBe "the-project-name"
        val tags = (captor.getValue \ "tags").as[JsObject]
        (tags \ "X-Session-ID").as[String] mustBe "session-123"
        (tags \ "path").as[String] mustBe "/a/b/c"
        val detail = (captor.getValue \ "detail").as[JsObject]
        (detail \ "userType").as[String] mustBe "Agent"
        (detail \ "vrn").as[String] mustBe "123"
      }
    }
  }

  "sendExplicitEvent JsObject" should {
    "call Datastream with tags read from headerCarrier and pass through detail" in {
      when(mockSimpleDatastreamHandler.sendEvent(any[JsValue])(any[ExecutionContext]))
        .thenReturn(Future.successful(HandlerResult.Success))

      val expectedDetail = Json.obj("Address" -> Json.obj("line1" -> "Road", "postCode" -> "123"))
      val headerCarrier = HeaderCarrier(sessionId = Some(SessionId("session-123")), otherHeaders = Seq("path" -> "/a/b/c"))
      mockConnector(enabledConfig).sendExplicitAudit("theAuditType", expectedDetail)(headerCarrier, ec)

      val captor = ArgumentCaptor.forClass(classOf[JsValue])
      verify(mockSimpleDatastreamHandler).sendEvent(captor.capture())(any[ExecutionContext])
      (captor.getValue \ "auditSource").as[String] mustBe "the-project-name"
      val tags = (captor.getValue \ "tags").as[JsObject]
      (tags \ "X-Session-ID").as[String] mustBe "session-123"
      (tags \ "path").as[String] mustBe "/a/b/c"
      val detail = (captor.getValue \ "detail").as[JsObject]
      detail mustBe expectedDetail
    }
  }
}
