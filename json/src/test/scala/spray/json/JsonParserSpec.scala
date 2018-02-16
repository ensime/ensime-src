// Copyright: 2010 - 2017 https://github.com/ensime/ensime-server/graphs/contributors
// License: http://www.gnu.org/licenses/lgpl-3.0.en.html

package spray.json

import org.scalatest._

class JsParserSpec extends WordSpec with Matchers {

  "The JsParser" should {
    "parse 'null' to JsNull" in {
      JsParser("null") should ===(JsNull)
    }
    "parse 'true' to JsBoolean.True" in {
      JsParser("true") should ===(JsBoolean.True)
    }
    "parse 'false' to JsBoolean.False" in {
      JsParser("false") should ===(JsBoolean.False)
    }
    "parse '0' to JsNumber" in {
      JsParser("0") should ===(JsNumber(0))
    }
    "parse '1.23' to JsNumber" in {
      JsParser("1.23") should ===(JsNumber(1.23))
    }
    "parse '-1E10' to JsNumber" in {
      JsParser("-1E10") should ===(JsNumber("-1E+10"))
    }
    "parse '12.34e-10' to JsNumber" in {
      JsParser("12.34e-10") should ===(JsNumber("1.234E-9"))
    }
    "parse \"xyz\" to JsString" in {
      JsParser("\"xyz\"") should ===(JsString("xyz"))
    }
    "parse escapes in a JsString" in {
      JsParser(""""\"\\/\b\f\n\r\t"""") should ===(JsString("\"\\/\b\f\n\r\t"))
      JsParser("\"L\\" + "u00e4nder\"") should ===(JsString("Länder"))
    }
    "parse all representations of the slash (SOLIDUS) character in a JsString" in {
      JsParser("\"" + "/\\/\\u002f" + "\"") should ===(JsString("///"))
    }
    "parse a simple JsObject" in (
      JsParser(""" { "key" :42, "key2": "value" }""") should ===(
        JsObject("key" -> JsNumber(42), "key2" -> JsString("value"))
      )
    )
    "parse a simple JsArray" in (
      JsParser("""[null, 1.23 ,{"key":true } ] """) should ===(
        JsArray(JsNull, JsNumber(1.23), JsObject("key" -> JsBoolean.True))
      )
    )
    "parse directly from UTF-8 encoded bytes" in {
      val json = JsObject(
        "7-bit"   -> JsString("This is regular 7-bit ASCII text."),
        "2-bytes" -> JsString("2-byte UTF-8 chars like £, æ or Ö"),
        "3-bytes" -> JsString("3-byte UTF-8 chars like ﾖ, ᄅ or ᐁ."),
        "4-bytes" -> JsString(
          "4-byte UTF-8 chars like \uD801\uDC37, \uD852\uDF62 or \uD83D\uDE01."
        )
      )
      JsParser(PrettyPrinter(json).getBytes("UTF-8")) should ===(json)
    }
    "parse directly from UTF-8 encoded bytes when string starts with a multi-byte character" in {
      val json = JsString("£0.99")
      JsParser(PrettyPrinter(json).getBytes("UTF-8")) should ===(json)
    }
    "be reentrant" in {
      val largeJsonSource = scala.io.Source
        .fromInputStream(getClass.getResourceAsStream("/test.json"))
        .mkString
      import scala.collection.parallel.immutable.ParSeq
      ParSeq.fill(20)(largeJsonSource).map(JsParser(_)).toList.map {
        _.asInstanceOf[JsObject]
          .fields("questions")
          .asInstanceOf[JsArray]
          .elements
          .size
      } should ===(List.fill(20)(100))
    }

    "produce proper error messages" in {
      def errorMessage(input: String) =
        try JsParser(input)
        catch { case e: JsParser.ParsingException => e.getMessage }

      errorMessage("""[null, 1.23 {"key":true } ]""") should ===(
        """Unexpected character '{' at input index 12 (line 1, position 13), expected ']':
          |[null, 1.23 {"key":true } ]
          |            ^
          |""".stripMargin
      )

      errorMessage("""[null, 1.23, {  key":true } ]""") should ===(
        """Unexpected character 'k' at input index 16 (line 1, position 17), expected '"':
          |[null, 1.23, {  key":true } ]
          |                ^
          |""".stripMargin
      )

      errorMessage("""{"a}""") should ===(
        """Unexpected end-of-input at input index 4 (line 1, position 5), expected '"':
          |{"a}
          |    ^
          |""".stripMargin
      )

      errorMessage("""{}x""") should ===(
        """Unexpected character 'x' at input index 2 (line 1, position 3), expected end-of-input:
          |{}x
          |  ^
          |""".stripMargin
      )
    }
  }
}
