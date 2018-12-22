package controllers

import java.sql.Timestamp
import java.util.UUID

import javax.inject.Inject
import com.mohiva.play.silhouette.api.{Environment, Silhouette}
import com.mohiva.play.silhouette.impl.authenticators.SessionAuthenticator
import com.vividsolutions.jts.geom._
import controllers.headers.ProvidesHeader
import formats.json.ValidationTaskSubmissionFormats._
import models.amt.{AMTAssignment, AMTAssignmentTable}
import models.audit._
import models.daos.slick.DBTableDefinitions.{DBUser, UserTable}
import models.gsv.{GSVData, GSVDataTable, GSVLink, GSVLinkTable}
import models.label._
import models.label.LabelValidationTable._
import models.label.LabelTable.LabelValidationMetadata
import models.mission.{Mission, MissionTable}
import models.user.{User, UserCurrentRegionTable}
import models.validation._
import org.joda.time.{DateTime, DateTimeZone}
import play.api.Logger
import play.api.libs.json._
import play.api.mvc._

import scala.concurrent.Future

class ValidationTaskController @Inject() (implicit val env: Environment[User, SessionAuthenticator])
  extends Silhouette[User, SessionAuthenticator] with ProvidesHeader {

  case class ValidationTaskPostReturnValue(mission: Option[Mission])
  /**
    * Parse submitted validation data and submit to tables
    * Useful info: https://www.playframework.com/documentation/2.6.x/ScalaJsonHttp
    * BodyParsers.parse.json in async
    */

  def post = UserAwareAction.async(BodyParsers.parse.json) { implicit request =>
    println("[ValidationTaskController] request.body: " + request.body)
    var submission = request.body.validate[Seq[ValidationTaskSubmission]]
    println("[ValidationTaskController] submission: " + submission)
    submission.fold(
      errors => {
        println("[ValidationTaskController] Error: " + errors);
        Future.successful(BadRequest(Json.obj("status" -> "Error", "message" -> JsError.toFlatJson(errors))))
      },
      submission => {
        val user = request.identity
        val returnValues: Seq[ValidationTaskPostReturnValue] = for (data <- submission) yield {
          println("[Data] " + data)
          for (interaction: InteractionSubmission <- data.interactions) {
            ValidationTaskInteractionTable.save(ValidationTaskInteraction(0, interaction.missionId, interaction.action,
              interaction.gsvPanoramaId, interaction.lat, interaction.lng, interaction.heading, interaction.pitch,
              interaction.zoom, interaction.note, new Timestamp(interaction.timestamp)))
          }

          for (label: LabelValidationSubmission <- data.labels) {
            user match {
              case Some(user) =>
                LabelValidationTable.save(LabelValidation(0, label.labelId, label.validationResult,
                  user.userId.toString, label.missionId, new Timestamp(label.startTimestamp),
                  new Timestamp(label.endTimestamp)))
              case None =>
                Logger.warn("User without user_id validated a label, but every user should have a user_id.")
            }
          }

          val missionId: Int = data.missionProgress.missionId
          println("missionId: " + missionId)
          val possibleNewMission: Option[Mission] = updateMissionTable(user, data.missionProgress)
          println("[ValidationTaskController] possibleMission: " + possibleNewMission)
          ValidationTaskPostReturnValue(possibleNewMission)

        }
        println("[ValidationTaskController] Success");
        Future.successful(Ok(Json.obj("mission" -> returnValues.head.mission.map(_.toJSON))))
      }
    )
  }

  /**
    * This function gets the metadata for a specific label in the database.
    * @param labelId  label_id for this label
    * @return Label metadata containing GSV metadata and label type
    */
  def getLabelData(labelId: Int) = UserAwareAction.async { implicit request =>
    LabelTable.find(labelId) match {
      case Some(labelPointObj) =>
        val labelMetadata: LabelValidationMetadata = LabelTable.retrieveSingleLabelForValidation(labelId)
        val labelMetadataJson: JsObject = LabelTable.validationLabelMetadataToJson(labelMetadata)
        Future.successful(Ok(labelMetadataJson))
      case _ => Future.successful(Ok(Json.obj("error" -> "no such label")))
    }
  }

  /**
    * This function gets the metadata for a random label in the database.
    * @return Label metadata containing GSV metadata and label type
    */
  def getRandomLabelData = UserAwareAction.async { implicit request =>
    val labelMetadata: LabelValidationMetadata = LabelTable.retrieveSingleRandomLabelForValidation()
    val labelMetadataJson: JsObject = LabelTable.validationLabelMetadataToJson(labelMetadata)
    Future.successful(Ok(labelMetadataJson))
  }

  def getRandomLabelDataList(count: Int) = UserAwareAction.async { implicit request =>
    val labelMetadata: Seq[LabelValidationMetadata] = LabelTable.retrieveRandomLabelListForValidation(count)
    val labelMetadataJson: Seq[JsObject] = labelMetadata.map(label => LabelTable.validationLabelMetadataToJson(label))
    Future.successful(Ok(Json.obj("labels" -> labelMetadataJson)))
  }

  /**
    *
    * @param user
    * @param missionProgress  Metadata for this mission
    * @return
    */
  def updateMissionTable(user: Option[User], missionProgress: ValidationMissionProgress): Option[Mission] = {
    val missionId: Int = missionProgress.missionId
    val skipped: Boolean = missionProgress.skipped
    val userId: UUID = user.get.userId
    val role: String = user.get.role.getOrElse("")

    //  (missionProgress.labelsProgress.isEmpty) Logger.error("Received null labels progress for validation mission.")
    val labelsProgress: Int = missionProgress.labelsProgress
    println("[updateMissionTable]: " + missionId + ", " + skipped + ", " + userId + ", " + role);

    if (missionProgress.completed) {
      println("[updateMissionTable] This mission is complete")
      // TODO: replace 0.0 with pay per label...
      MissionTable.updateCompleteAndGetNextValidationMission(userId, 0.0, missionId, labelsProgress, skipped)
    } else {
      println("[updateMissionTable] This mission is not complete")
      MissionTable.updateValidationProgressOnly(userId, missionId, labelsProgress)
    }
  }
}
