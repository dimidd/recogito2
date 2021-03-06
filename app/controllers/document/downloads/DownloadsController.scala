package controllers.document.downloads

import akka.util.ByteString
import akka.stream.scaladsl.Source
import com.mohiva.play.silhouette.api.Silhouette
import com.mohiva.play.silhouette.api.actions.UserAwareRequest
import controllers.{BaseOptAuthController, Security}
import controllers.document.downloads.serializers._
import javax.inject.{Inject, Singleton}
import services.ContentType
import services.annotation.AnnotationService
import services.document.{DocumentInfo, DocumentService, RuntimeAccessLevel}
import services.entity.builtin.EntityService
import services.user.UserService
import org.apache.jena.riot.RDFFormat
import org.webjars.play.WebJarsUtil
import play.api.{Configuration, Logger}
import play.api.libs.json._
import play.api.libs.json.Reads._
import play.api.libs.functional.syntax._
import play.api.libs.iteratee.Enumerator
import play.api.libs.Files.TemporaryFileCreator
import play.api.i18n.I18nSupport
import play.api.mvc.{AnyContent, ControllerComponents, Result}
import play.api.http.{HttpEntity, FileMimeTypes}
import scala.concurrent.{ExecutionContext, Future}
import storage.uploads.Uploads

case class FieldMapping(
  BASE_URL          : Option[String],
  FIELD_ID          : Int,
  FIELD_TITLE       : Int,
  FIELDS_NAME       : Option[Int],
  FIELD_DESCRIPTION : Option[Int],
  FIELD_COUNTRY     : Option[Int],
  FIELD_LATITUDE    : Option[Int],
  FIELD_LONGITUDE   : Option[Int])
  
object FieldMapping {
  
  implicit val fieldMappingReads: Reads[FieldMapping] = (
    (JsPath \ "base_url").readNullable[String] and
    (JsPath \ "id").read[Int] and
    (JsPath \ "title").read[Int] and
    (JsPath \ "name").readNullable[Int] and
    (JsPath \ "description").readNullable[Int] and
    (JsPath \ "country").readNullable[Int] and
    (JsPath \ "latitude").readNullable[Int] and
    (JsPath \ "longitude").readNullable[Int]
  )(FieldMapping.apply _)
  
}

@Singleton
class DownloadsController @Inject() (
  val components: ControllerComponents,
  val users: UserService,
  val silhouette: Silhouette[Security.Env],
  implicit val config: Configuration,
  implicit val mimeTypes: FileMimeTypes,
  implicit val tmpFile: TemporaryFileCreator,
  implicit val uploads: Uploads,
  implicit val annotations: AnnotationService,
  implicit val documents: DocumentService,
  implicit val entities: EntityService,
  implicit val webjars: WebJarsUtil,
  implicit val ctx: ExecutionContext
) extends BaseOptAuthController(components, config, documents, users)
    with annotations.csv.AnnotationsToCSV
    with annotations.oa.AnnotationsToOA
    with annotations.webannotation.AnotationsToWebAnno
    with document.csv.DatatableToCSV
    with document.geojson.DatatableToGazetteer
    with document.tei.PlaintextToTEI
    with document.tei.TEIToTEI
    with places.PlacesToGeoJSON
    with relations.RelationsToGephi
    with I18nSupport {
  
  private def download(
    documentId: String, 
    requiredAccessLevel: RuntimeAccessLevel, 
    export: DocumentInfo => Future[Result]
  )(implicit request: UserAwareRequest[Security.Env, AnyContent]) = 
    documentReadResponse(documentId, request.identity, { case (docInfo, userAccessLevel) =>
      if (userAccessLevel >= requiredAccessLevel)
        export(docInfo)
      else
        Future.successful(Forbidden)
    })

  def showDownloadOptions(documentId: String) = silhouette.UserAwareAction.async { implicit request =>
    documentReadResponse(documentId, request.identity, { case (doc, accesslevel) =>
      
      val fAnnotationCount = annotations.countByDocId(documentId)
      val fHasRelations = annotations.hasRelations(documentId)
      
      val f = for {
        annotationCount <- fAnnotationCount
        hasRelations <- fHasRelations
      } yield (annotationCount, hasRelations)
      
      f.map { case (annotationCount, hasRelations) =>
        Ok(views.html.document.downloads.index(doc, request.identity, accesslevel, annotationCount, hasRelations))
      }
    })
  }

  /** Exports either 'plain' annotation CSV, or merges annotations with original DATA_* uploads, if any **/
  def downloadCSV(documentId: String, exportTables: Boolean) = silhouette.UserAwareAction.async { implicit request =>
    documentReadResponse(documentId, request.identity, { case (docInfo, userAccessLevel) =>
      if (exportTables)
        // Merged table export requires READ_ALL privileges
        if (userAccessLevel.canReadAll)
          exportMergedTables(docInfo).map { case (file, filename) =>
            Ok.sendFile(file).withHeaders(CONTENT_DISPOSITION -> { "attachment; filename=" + filename })
          }
        else
          Future.successful(Forbidden)
      else
        // Normal table export only requires READ_DATA privileges
        if (userAccessLevel.canReadData)
          annotationsToCSV(documentId).map { csv =>
            Ok.sendFile(csv).withHeaders(CONTENT_DISPOSITION -> { "attachment; filename=" + documentId + ".csv" })
          }
        else
          Future.successful(Forbidden)    
    })
  }
  
  private def downloadRDF(documentId: String, format: RDFFormat, extension: String) = silhouette.UserAwareAction.async { implicit request =>
    download(documentId, RuntimeAccessLevel.READ_DATA, { doc =>
      documentToRDF(doc, format).map { file => 
        Ok.sendFile(file).withHeaders(CONTENT_DISPOSITION -> { "attachment; filename=" + documentId + "." + extension })
      }
    })
  }
  
  def downloadTTL(documentId: String) = downloadRDF(documentId, RDFFormat.TTL, "ttl") 
  def downloadRDFXML(documentId: String) = downloadRDF(documentId, RDFFormat.RDFXML, "rdf.xml") 
  
  def downloadJSONLD(documentId: String) = silhouette.UserAwareAction.async { implicit request =>
    download(documentId, RuntimeAccessLevel.READ_DATA, { doc =>
      documentToWebAnnotation(doc).map { json =>
        Ok(Json.prettyPrint(json))
          .withHeaders(CONTENT_DISPOSITION -> { "attachment; filename=" + documentId + ".jsonld" })
      }
    })
  }

  def downloadGeoJSON(documentId: String, asGazetteer: Boolean) = silhouette.UserAwareAction.async { implicit request =>
    
    // Standard GeoJSON download
    def downloadPlaces() =
      placesToGeoJSON(documentId).map { featureCollection =>
        Ok(Json.prettyPrint(featureCollection))
          .withHeaders(CONTENT_DISPOSITION -> { "attachment; filename=" + documentId + ".json" })
      }
    
    // Places + spreadsheet info, according to Pelagios gazetteer GeoJSON conventions
    def downloadGazetteer(doc: DocumentInfo) = request.body.asFormUrlEncoded.flatMap(_.get("json").flatMap(_.headOption)) match {
      case Some(str) =>
        Json.fromJson[FieldMapping](Json.parse(str)) match {
          case result: JsSuccess[FieldMapping] =>
            exportGeoJSONGazetteer(doc, result.get).map { featureCollection =>
              Ok(Json.prettyPrint(featureCollection))
                .withHeaders(CONTENT_DISPOSITION -> { "attachment; filename=" + documentId + ".json" })
            }
              
          case error: JsError =>
            Logger.warn("Attempt to download gazetteer but field mapping invalid: " + str + "\n" + error)
            Future.successful(BadRequest)
        }
          
      case None =>
        Logger.warn("Attempt to download gazetteer without field mapping payload")
        Future.successful(BadRequest)
    }

     documentReadResponse(documentId, request.identity, { case (docInfo, userAccessLevel) =>
       if (asGazetteer)
         // Download as gazetteer requires READ_ALL privileges
         if (userAccessLevel.canReadAll)
           downloadGazetteer(docInfo)
         else
           Future.successful(Forbidden)
       else
         // GeoJSON download only requires READ_DATA privileges
         if (userAccessLevel.canReadData)
           downloadPlaces
         else
           Future.successful(Forbidden)
     })     
  }
  
  def downloadGephiNodes(documentId: String) = silhouette.UserAwareAction.async { implicit request =>
    relationsToGephiNodes(documentId).map { file =>
      Ok.sendFile(file).withHeaders(CONTENT_DISPOSITION -> { "attachment; filename=" + documentId + "_nodes.csv" })    
    }
  }
  
  def downloadGephiEdges(documentId: String) = silhouette.UserAwareAction.async { implicit request =>
    relationsToGephiEdges(documentId).map { file =>
      Ok.sendFile(file).withHeaders(CONTENT_DISPOSITION -> { "attachment; filename=" + documentId + "_edges.csv" })    
    }
  }
  
  def downloadTEI(documentId: String) = silhouette.UserAwareAction.async { implicit request =>
    download(documentId, RuntimeAccessLevel.READ_ALL, { doc =>
      val contentTypes = doc.fileparts.flatMap(pt => ContentType.withName(pt.getContentType)).distinct
      
      // At the moment, we only support TEI download for documents that are either plaintext- or TEI-only
      if (contentTypes.size != 1) {
        Future.successful(BadRequest("unsupported document content type(s)"))
      } else if (!contentTypes.head.isText) {
        Future.successful(BadRequest("unsupported document content type(s)"))
      } else {
        val f = contentTypes.head match {
          case ContentType.TEXT_PLAIN => plaintextToTEI(doc)
          case ContentType.TEXT_TEIXML => teiToTEI(doc)
          case _ => throw new Exception // Can't happen under current conditions (and should fail in the future if things go wrong)
        }
        
        f.map { xml => 
          Ok(xml).withHeaders(CONTENT_DISPOSITION -> { s"attachment; filename=${documentId}.tei.xml" })
        }
      }
    })
  }

}
