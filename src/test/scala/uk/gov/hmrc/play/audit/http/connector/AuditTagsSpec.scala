/*
 * Copyright 2018 HM Revenue & Customs
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

import org.scalatest.{Matchers, WordSpecLike}
import uk.gov.hmrc.play.audit.AuditExtensions
import uk.gov.hmrc.play.audit.EventKeys._
import uk.gov.hmrc.http.{HeaderCarrier, HeaderNames, Token, UserId}
import uk.gov.hmrc.http.logging._

class AuditTagsSpec extends WordSpecLike with Matchers {

  import uk.gov.hmrc.http.HeaderNames._
  import AuditExtensions._

  val authorization = Authorization("authorization")
  val userId = UserId("userId")
  val token = Token("token")
  val forwarded = ForwardedFor("ipAdress")
  val sessionId = SessionId("1234567890")
  val requestId = RequestId("0987654321")
  val deviceId = "testDeviceId"
  val akamaiReputation = AkamaiReputation("foo")

  "Audit TAGS" should {
    "be present" in {
      val hc = HeaderCarrier(Some(authorization), Some(userId), Some(token), Some(forwarded), Some(sessionId), Some(requestId), deviceID = Some(deviceId), akamaiReputation = Some(akamaiReputation))

      val tags = hc.toAuditTags("theTransactionName", "/the/request/path")

      tags.size shouldBe 7

      tags(xSessionId) shouldBe sessionId.value
      tags(xRequestId) shouldBe requestId.value
      tags(TransactionName) shouldBe "theTransactionName"
      tags(Path) shouldBe "/the/request/path"
      tags("clientIP") shouldBe "-"
      tags("clientPort") shouldBe "-"
      tags("Akamai-Reputation") shouldBe akamaiReputation.value
    }

    "be defaulted" in {
      val hc = HeaderCarrier()

      val tags = hc.toAuditTags("defaultsWhenNothingSet", "/the/request/path")

      tags.size shouldBe 7

      tags(xSessionId) shouldBe "-"
      tags(xRequestId) shouldBe "-"
      tags(TransactionName) shouldBe "defaultsWhenNothingSet"
      tags(Path) shouldBe "/the/request/path"
      tags("clientIP") shouldBe "-"
      tags("clientPort") shouldBe "-"
      tags("Akamai-Reputation") shouldBe "-"
    }

    "have more tags.clientIP and tags.clientPort" in {
      val hc = HeaderCarrier(trueClientIp = Some("192.168.1.1"), trueClientPort =Some("9999"))

      val tags = hc.toAuditTags("defaultsWhenNothingSet", "/the/request/path")

      tags("clientIP") shouldBe "192.168.1.1"
      tags("clientPort") shouldBe "9999"
    }

  }

  "Audit DETAILS" should {
    "be present" in {
      val hc = HeaderCarrier(Some(authorization), Some(userId), Some(token), Some(forwarded), Some(sessionId), Some(requestId), deviceID = Some(deviceId))

      val details = hc.toAuditDetails()

      details.size shouldBe 4

      details("ipAddress") shouldBe forwarded.value
      details(authorisation) shouldBe authorization.value
      details(HeaderNames.token) shouldBe token.value
      details(HeaderNames.deviceID) shouldBe deviceId
    }

    "be defaulted" in {
      val hc = HeaderCarrier()

      val details = hc.toAuditDetails()

      details.size shouldBe 4

      details("ipAddress") shouldBe "-"
      details(authorisation) shouldBe "-"
      details(HeaderNames.token) shouldBe "-"
      details(HeaderNames.deviceID) shouldBe "-"
    }

    "have more details only" in {
      val hc = HeaderCarrier(trueClientIp = Some("192.168.1.1"), trueClientPort =Some("9999"))

      val details = hc.toAuditDetails("more-details" -> "the details", "lots-of-details" -> "interesting info")

      details.size shouldBe 6

      details("more-details") shouldBe "the details"
      details("lots-of-details") shouldBe "interesting info"
    }

  }

}
