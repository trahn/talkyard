/**
 * Copyright (c) 2015 Kaj Magnus Lindberg
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package debiki.dao

import com.debiki.core._
import com.debiki.core.Prelude._
import debiki.{Globals, TextAndHtml, TextAndHtmlMaker}
import ed.server.{EdAppComponents, EdContext}
import org.scalatest._
import org.scalatestplus.play.{BaseOneAppPerSuite, BaseOneAppPerTest, FakeApplicationFactory}
import DaoAppSuite._
import java.io.File

import play.api.inject.DefaultApplicationLifecycle
import play.api._
import play.core.DefaultWebCommands
import talkyard.server.DeleteWhatSite



object DaoAppSuite {

  /** If the test start time is less than a year after 1970, the popularity stats will
    * subtract a year and create a negative Unix-millis-time —> an assertion fails. So start
    * at least a year after 1970 — let's say 1157 days, to get a nice looking number: 100...000.
    */
  val OneAndZeros1157DaysInMillis = 100000000000L // divide by (24*3600*1000) —> 1157.4 days

}


class DaoAppSuite(
  val disableScripts: Boolean = true,
  val disableBackgroundJobs: Boolean = true,
  val butEnableJanitor: Boolean = false,
  val maxSitesTotal: Option[Int] = None,
  val minPasswordLength: Option[Int] = None,
  val startTime: When = When.fromMillis(10 * 1000 + OneAndZeros1157DaysInMillis),
  val extraConfig: Map[String, String] = Map.empty)
  extends FreeSpec with MustMatchers with BaseOneAppPerSuite with FakeApplicationFactory {

  Globals.setIsProdForever(false)

  private var edAppComponents: EdAppComponents = _

  lazy val context: EdContext = edAppComponents.context
  lazy val globals: Globals = context.globals
  lazy val textAndHtmlMaker: TextAndHtmlMaker = new TextAndHtmlMaker("testsiteid", context.nashorn)


  override def fakeApplication: Application = {
    val env = Environment.simple(new File("."))
    val fileConfig = Configuration.load(env)
    val totalConfig = fileConfig ++ testConfig
    val appLoaderContext = ApplicationLoader.Context(
      environment = env,
      sourceMapper = None,
      webCommands = new DefaultWebCommands(),
      initialConfiguration = totalConfig,
      lifecycle = new DefaultApplicationLifecycle()
    )

    LoggerConfigurator(env.classLoader).foreach {
      _.configure(env, totalConfig, optionalProperties = Map.empty)
    }

    edAppComponents = new EdAppComponents(appLoaderContext)
    setTime(startTime) // now 'globals' is available
    edAppComponents.application
  }


  private def testConfig: Configuration = {
    var configMap = Map[String, String](
      "isTestShallEmptyDatabase" -> "true",
      "isTestDisableScripts" -> (disableScripts ? "true" | "false"),
      "isTestDisableBackgroundJobs" -> (disableBackgroundJobs ? "true" | "false"),
      "isTestEnableJanitor" -> (butEnableJanitor ? "true" | "false"))
    import debiki.Config.CreateSitePath
    maxSitesTotal foreach { max =>
      configMap = configMap.updated(s"$CreateSitePath.maxSitesTotal", max.toString)
    }
    minPasswordLength foreach { min =>
      configMap = configMap.updated("talkyard.minPasswordLength", min.toString)
    }
    Configuration.from(extraConfig ++ configMap)
  }


  def browserIdData = BrowserIdData("1.2.3.4", idCookie = Some("dummy_id_cookie"), fingerprint = 334455)
  def dummySpamRelReqStuff = SpamRelReqStuff(userAgent = None, referer = None, uri = "/dummy",
    userName = None, userEmail = None, userUrl = None, userTrustLevel = None)


  private var _currentTime: When = _

  def currentTime: When = _currentTime

  def setTime(when: When) {
    _currentTime = when
    globals.testSetTime(when)
  }

  def playTimeSeconds(seconds: Long): Unit = playTimeMillis(seconds * 1000)

  def playTimeMillis(millis: Long) {
    _currentTime = _currentTime plusMillis millis
    globals.testSetTime(_currentTime)
  }


  def createSite(hostname: String, settings: SettingsToSave = SettingsToSave()): (Site, SiteDao) = {
    val siteName = "site-" + hostname.replaceAllLiterally(".", "")
    val site = globals.systemDao.createAdditionalSite(
      pubId = s"pubid-$siteName", name = siteName, status = SiteStatus.Active, hostname = Some(hostname),
      embeddingSiteUrl = None, organizationName = s"Site $hostname Organization Name",
      creatorId = UnknownUserId, browserIdData,
      isTestSiteOkayToDelete = true, skipMaxSitesCheck = true,
      deleteWhatSite = DeleteWhatSite.NoSite, pricePlan = "Unknown", createdFromSiteId = None)
    val dao = globals.siteDao(site.id)
    if (settings != SettingsToSave()) {
      dao.readWriteTransaction { tx =>
        tx.upsertSiteSettings(settings)
      }
    }
    (site, dao)
  }


  def createPasswordOwner(username: String, dao: SiteDao,
        createdAt: Option[When] = None, firstSeenAt: Option[When] = None,
        emailVerified: Boolean = false): User = {
    createPasswordAdminOrOwner(username, dao, createdAt = createdAt,
        firstSeenAt = firstSeenAt, isOwner = true, emailVerified = emailVerified)
  }

  /** Its name will be "Admin $username", username "admin_$username" and email
    * "admin-$username@x.co",
    */
  def createPasswordAdmin(username: String, dao: SiteDao, createdAt: Option[When] = None,
        firstSeenAt: Option[When] = None, emailVerified: Boolean = false): User = {
    createPasswordAdminOrOwner(username, dao, createdAt = createdAt,
      firstSeenAt = firstSeenAt, isOwner = false, emailVerified = emailVerified)
  }

  private def createPasswordAdminOrOwner(username: String, dao: SiteDao, isOwner: Boolean,
      createdAt: Option[When], firstSeenAt: Option[When] = None, emailVerified: Boolean = false)
      : User = {
    val theCreatedAt = createdAt.getOrElse(globals.now())
    val password = s"public-${username take 2}-x-${username drop 2}"
    val adm = dao.createPasswordUserCheckPasswordStrong(NewPasswordUserData.create(
      name = Some(s"Admin $username"), username = username,
      email = s"$username@x.co", password = Some(password),
      createdAt = theCreatedAt,
      isAdmin = true, isOwner = isOwner).get, browserIdData)
    if (emailVerified) {
      dao.verifyPrimaryEmailAddress(adm.id, theCreatedAt.toJavaDate)
    }
    adm
  }


  def createPasswordModerator(username: String, dao: SiteDao, createdAt: Option[When] = None,
        emailVerified: Boolean = false): User = {
    val theCreatedAt = createdAt.getOrElse(globals.now())
    val mod = dao.createPasswordUserCheckPasswordStrong(NewPasswordUserData.create(
      name = Some(s"Mod $username"), username = username, email = s"$username@x.co",
      password = Some(s"public-$username"), createdAt = theCreatedAt,
      isAdmin = false, isModerator = true, isOwner = false).get, browserIdData)
    if (emailVerified) {
      dao.verifyPrimaryEmailAddress(mod.id, theCreatedAt.toJavaDate)
    }
    mod
  }


  /** Its name will be "User $username", and email "$username@x.co".
    */
  def createPasswordUser(username: String, dao: SiteDao,
        trustLevel: TrustLevel = TrustLevel.NewMember,
        threatLevel: ThreatLevel = ThreatLevel.HopefullySafe,
        createdAt: Option[When] = None, emailVerified: Boolean = false,
        extId: Option[ExtImpId] = None): User = {
    val theCreatedAt = createdAt.getOrElse(globals.now())
    val member = dao.createPasswordUserCheckPasswordStrong(NewPasswordUserData.create(
      name = Some(s"User $username"), username = username, email = s"$username@x.co",
      password = Some(s"public-$username"), createdAt = theCreatedAt,
      isAdmin = false, isOwner = false, trustLevel = trustLevel, threatLevel = threatLevel,
      extId = extId).get,
      browserIdData)
    if (emailVerified) {
      dao.verifyPrimaryEmailAddress(member.id, theCreatedAt.toJavaDate)
    }
    member
  }


  def createGroup(dao: SiteDao, username: String, fullName: Option[String],
      createdAt: Option[When] = None, firstSeenAt: Option[When] = None): Group = {
    dao.createGroup(username, fullName, Who(SystemUserId, browserIdData)).get
  }


  def updateMemberPreferences(dao: SiteDao, memberId: UserId,
        fn: Function1[AboutUserPrefs, AboutUserPrefs]) {
    val member = dao.loadTheUserInclDetailsById(memberId)
    dao.saveAboutMemberPrefs(fn(member.preferences_debugTest), Who(memberId, browserIdData))
  }


  def updateGroupPreferences(dao: SiteDao, groupId: UserId, byWho: Who,
        fn: Function1[AboutGroupPrefs, AboutGroupPrefs]) {
    val group = dao.loadTheGroupInclDetailsById(groupId)
    dao.saveAboutGroupPrefs(fn(group.preferences), byWho)
  }


  def loadTheUserStats(userId: UserId)(dao: SiteDao): UserStats =
    dao.readOnlyTransaction(_.loadUserStats(userId)) getOrDie "EdE5JWGB10"


  def letEveryoneTalkAndStaffModerate(dao: SiteDao) {
    dao.readWriteTransaction { tx =>
      tx.insertPermsOnPages(PermsOnPages(
        id = NoPermissionId,
        forPeopleId = Group.EveryoneId,
        onWholeSite = Some(true),
        mayCreatePage = Some(true),
        mayPostComment = Some(true),
        maySee = Some(true)))

      tx.insertPermsOnPages(PermsOnPages(
        id = NoPermissionId,
        forPeopleId = Group.StaffId,
        onWholeSite = Some(true),
        mayEditPage = Some(true),
        mayEditComment = Some(true),
        mayEditWiki = Some(true),
        mayDeletePage = Some(true),
        mayDeleteComment = Some(true),
        mayCreatePage = Some(true),
        mayPostComment = Some(true),
        maySee = Some(true)))
    }
  }


  /*
  def createCategory(sectionPageId: PageId,
        bodyTextAndHtml: TextAndHtml, authorId: UserId, browserIdData: BrowserIdData,
        dao: SiteDao, anyCategoryId: Option[CategoryId] = None): (Category, Seq[PermsOnPages]) = {

    val categoryData: CategoryToSave = CategoryToSave(
      anyId = None, //Some(categoryId),
      sectionPageId = sectionPageId,
      parentId = (categoryJson \ "parentId").as[CategoryId],
      name = (categoryJson \ "name").as[String],
      slug = (categoryJson \ "slug").as[String].toLowerCase,
      description = CategoriesDao.CategoryDescriptionSource,
      position = (categoryJson \ "position").as[Int],
      newTopicTypes = List(defaultTopicType),
      shallBeDefaultCategory = shallBeDefaultCategory,
      unlistCategory = unlistCategory,
      unlistTopics = unlistTopics,
      includeInSummaries = includeInSummaries)

    val permissions = ArrayBuffer[PermsOnPages]()

    request.dao.createCategory(
      categoryData, permissions.to[immutable.Seq], request.who)
  } */


  def createPage(pageRole: PageType, titleTextAndHtml: TextAndHtml,
        bodyTextAndHtml: TextAndHtml, authorId: UserId, browserIdData: BrowserIdData,
        dao: SiteDao, anyCategoryId: Option[CategoryId] = None,
        extId: Option[ExtImpId] = None, discussionIds: Set[AltPageId] = Set.empty): PageId = {
    dao.createPage(pageRole, PageStatus.Published, anyCategoryId = anyCategoryId,
      anyFolder = Some("/"), anySlug = Some(""),
      titleTextAndHtml = titleTextAndHtml, bodyTextAndHtml = bodyTextAndHtml,
      showId = true, deleteDraftNr = None, Who(authorId, browserIdData), dummySpamRelReqStuff,
      discussionIds = discussionIds, extId = extId
    ).pageId
  }


  def reply(memberId: UserId, pageId: PageId, text: String, parentNr: Option[PostNr] = None,
        skipNashorn: Boolean = true)(dao: SiteDao): Post = {
    val textAndHtml =
      if (skipNashorn) textAndHtmlMaker.testBody(text)
      else textAndHtmlMaker.forBodyOrComment(text)
    dao.insertReply(textAndHtml, pageId,
      replyToPostNrs = Set(parentNr getOrElse PageParts.BodyNr), PostType.Normal, deleteDraftNr = None,
      Who(memberId, browserIdData), dummySpamRelReqStuff).post
  }


  def chat(memberId: UserId, pageId: PageId, text: String, skipNashorn: Boolean = true)(dao: SiteDao): Post = {
    val textAndHtml =
      if (skipNashorn) textAndHtmlMaker.testBody(text)
      else textAndHtmlMaker.forBodyOrComment(text)
    dao.insertChatMessage(textAndHtml, pageId, deleteDraftNr = None,
        Who(memberId, browserIdData), dummySpamRelReqStuff).post
  }


  def edit(post: Post, editorId: UserId, newText: String, skipNashorn: Boolean = true)(dao: SiteDao) {
    val textAndHtml =
      if (skipNashorn) textAndHtmlMaker.testBody(newText)
      else textAndHtmlMaker.forBodyOrComment(newText)
    dao.editPostIfAuth(post.pageId, post.nr, deleteDraftNr = None,
        Who(editorId, browserIdData), dummySpamRelReqStuff, textAndHtml)
  }



  def loadUserStats(userId: UserId)(dao: SiteDao): UserStats = {
    dao.readOnlyTransaction { transaction =>
      transaction.loadUserStats(userId) getOrDie "EdE4GPW945"
    }
  }


  def loadTheMemberAndStats(userId: UserId)(dao: SiteDao): (User, UserStats) = {
    dao.readOnlyTransaction { transaction =>
      val member = transaction.loadTheUser(userId)
      val stats = transaction.loadUserStats(userId) getOrDie "EdE2FK4GS"
      (member, stats)
    }
  }

}
