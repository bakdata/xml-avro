package in.dreamlabs.xmlavro

import java.io._
import javax.xml.stream.XMLStreamConstants._
import javax.xml.stream.events.{Attribute, EndElement, StartElement, XMLEvent}
import javax.xml.stream.{XMLEventReader, XMLInputFactory}

import in.dreamlabs.xmlavro.AvroBuilder.unknown
import in.dreamlabs.xmlavro.RichAvro._
import in.dreamlabs.xmlavro.XMLEvents.{addElement, eleStack, removeElement}
import in.dreamlabs.xmlavro.config.XMLConfig
import org.apache.avro.Schema
import org.apache.avro.file.{CodecFactory, DataFileWriter}
import org.apache.avro.generic.GenericData.Record
import org.apache.avro.specific.SpecificDatumWriter

import scala.collection.JavaConverters._
import scala.collection.mutable

/**
  * Created by Royce on 25/01/2017.
  */
class AvroBuilder(config: XMLConfig) {
  Utils.debugEnabled = config.debug
  RichAvro.caseSensitive = config.caseSensitive
  RichAvro.ignoreCaseFor = config.ignoreCaseFor.asScala.toList.map(element => element.toLowerCase)
  RichAvro.ignoreMissing = config.ignoreMissing
  RichAvro.suppressWarnings = config.suppressWarnings
  XNode.namespaces = config.namespaces
  XMLDocument.config = config

  def createDatums(): Unit = {
    val xmlIn =
      if (config.streamingInput) new BufferedInputStream(System.in)
      else config.xmlFile.toFile.bufferedInput()

    val reader = XMLInputFactory.newInstance.createXMLEventReader(xmlIn)
    val writers = mutable.Map[String, DataFileWriter[Record]]()
    val schemas = mutable.Map[String, Schema]()
    val streams = mutable.ListBuffer[OutputStream]()
    config.split.forEach { split =>
      val schema = new Schema.Parser().parse(split.avscFile.jfile)
      val datumWriter = new SpecificDatumWriter[Record](schema)
      val fileWriter = new DataFileWriter[Record](datumWriter)

      fileWriter setCodec (CodecFactory snappyCodec)
      val avroOut =
        if (split stream) new BufferedOutputStream(System.out)
        else split.avroFile.toFile.bufferedOutput()
      fileWriter create(schema, avroOut)
      streams += avroOut
      writers += split.by -> fileWriter
      schemas += split.by -> schema
    }

    var splitRecord: Record = null
    var splitFound, documentFound: Boolean = false
    var proceed: Boolean = false
    var parentEle: String = ""
    var currentDoc: Option[XMLDocument] = None
    var prevEvent: XMLEvent = null

    reader.dropWhile(!_.isStartElement) foreach { event =>
      try {
        if (currentDoc isDefined)
          currentDoc.get add event
        event getEventType match {
          case START_DOCUMENT | END_DOCUMENT => //Ignore
          case START_ELEMENT =>
            if (writers contains "") {
              writers += event.name -> writers("")
              schemas += event.name -> schemas("")
              writers remove ""
              schemas remove ""
            }
            if (config.documentRootTag == event.name) {
              documentFound = true
              proceed = true
              splitFound = false
              currentDoc = Some(XMLDocument())
              currentDoc.get add event
            }

            if (currentDoc.isDefined && !currentDoc.get.error) {
              if (writers.contains(event.name)) {
                if (splitFound)
                  ConversionError(
                    "Splits cannot be inside each other, they should be completely separated tags")
                splitFound = true
                splitRecord = schemas(event name).newRecord
                XMLEvents.setSchema(schemas(event name), splitRecord)
                AvroPath.reset()
                proceed = true
              }
              if (splitFound && proceed) {
                proceed = event push()
                parentEle = event.name
                if (event.hasAttributes && proceed) {
                  val record = splitRecord.at(event path)
                  event.attributes foreach {
                    case (xEle, value) =>
                      record.add(xEle, value)
                  }
                }
              }
            }
          case CHARACTERS =>
            if (splitFound && proceed && currentDoc.isDefined && !currentDoc.get.error && event.hasText) {
              val record = splitRecord.at(event path)
              record.add(event element, event text)
            }
          case END_ELEMENT =>
            if (splitFound && proceed && currentDoc.isDefined && !currentDoc.get.error && prevEvent.isStartElement) {
              val path = event.path.last.name
              if (path != event.name) {
                val record = splitRecord.at(event path)
                record.add(event element, "")
              }
            }
            if (currentDoc.isDefined && !currentDoc.get.error) {
              if (splitFound && (proceed || event.name == parentEle)) {
                proceed = true
                event pop()
                if (writers.contains(event.name)) {
                  val writer = writers(event name)
                  writer append splitRecord
                  splitFound = false
                }
              }
            }
          case other => unknown(other.toString, event)
        }
      } catch {
        case e: Exception =>
          if (currentDoc isDefined) currentDoc.get fail e
          else throw new ConversionError(e)
          proceed = false
      } finally {
        if (event.isEndElement && config.documentRootTag == event.name) {
          documentFound = false
          currentDoc.get close()
          currentDoc = None
        }
        prevEvent = event
      }
    }

    writers.values.foreach { writer =>
      writer.flush()
      writer.close()
    }
    streams.foreach(_.close())
    xmlIn.close()
    XMLDocument.closeAll()
  }

  implicit class RichXMLEventIterator(reader: XMLEventReader)
    extends Iterator[XMLEvent] {
    def hasNext: Boolean = reader hasNext

    def next: XMLEvent = reader nextEvent
  }

  implicit class RichXMLEvent(event: XMLEvent) {

    private val startEle: Option[StartElement] =
      if (event isStartElement)
        Some(event.asStartElement())
      else
        None

    private val endEle: Option[EndElement] =
      if (event isEndElement)
        Some(event.asEndElement())
      else
        None

    val attributes: mutable.LinkedHashMap[XNode, String] = {
      val attrMap = mutable.LinkedHashMap.empty[XNode, String]
      if (startEle isDefined) {
        val attrs = startEle.get.getAttributes
        while (attrs.hasNext) {
          val attr = attrs.next().asInstanceOf[Attribute]
          val name = attr.getName
          if (name.getLocalPart.toLowerCase() != "schemalocation")
            attrMap += XNode(name.getLocalPart,
              name.getNamespaceURI,
              name.getPrefix,
              attribute = true) -> attr.getValue
        }
      }
      attrMap
    }

    def path: List[AvroPath] = XMLEvents.schemaPath.toList

    def hasAttributes: Boolean = attributes nonEmpty

    def push(): Boolean = {
      if (eleStack.isEmpty)
        addElement(XNode(name, nsURI, nsName, attribute = false))
      else addElement(XNode(element, name, nsURI, nsName, attribute = false))
    }

    private def nsURI: String =
      if (startEle isDefined) startEle.get.getName.getNamespaceURI
      else if (endEle isDefined) endEle.get.getName.getNamespaceURI
      else element.nsURI

    private def nsName: String =
      if (startEle isDefined) startEle.get.getName.getPrefix
      else if (endEle isDefined) endEle.get.getName.getPrefix
      else element.nsName

    def element: XNode = eleStack.head

    def name: String =
      if (startEle isDefined) startEle.get.getName.getLocalPart
      else if (endEle isDefined) endEle.get.getName.getLocalPart
      else element.name

    def pop(): Unit =
      removeElement(XNode(name, nsURI, nsName, attribute = false))

    def text: String = event.asCharacters().getData

    def hasText: Boolean = text.trim() != "" || text.matches(" +")
  }

}

object AvroBuilder {
  private def unknown(message: String, event: XMLEvent) =
    Utils.warn(s"WARNING: Unknown $message: $event")
}


