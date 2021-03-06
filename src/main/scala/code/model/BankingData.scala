/**
Open Bank Project - API
Copyright (C) 2011-2016, TESOBE Ltd

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.

Email: contact@tesobe.com
TESOBE Ltd
Osloerstrasse 16/17
Berlin 13359, Germany

  This product includes software developed at
  TESOBE (http://www.tesobe.com/)
  by
  Simon Redfern : simon AT tesobe DOT com
  Stefan Bethge : stefan AT tesobe DOT com
  Everett Sochowski : everett AT tesobe DOT com
  Ayoub Benali: ayoub AT tesobe DOT com

 */
package code.model

import java.util.Date
import code.api.util.ErrorMessages._
import code.accountholder.AccountHolders
import code.api.util.APIUtil.hasEntitlement
import code.api.util.{APIUtil, ApiRole, CallContext, ErrorMessages}
import code.bankconnectors.vJune2017.AccountRule
import code.bankconnectors.{Connector, OBPQueryParam}
import code.metadata.comments.Comments
import code.metadata.counterparties.{Counterparties, CounterpartyTrait}
import code.metadata.narrative.Narrative
import code.metadata.tags.Tags
import code.metadata.transactionimages.TransactionImages
import code.metadata.wheretags.WhereTags
import code.util.Helper
import code.util.Helper.MdcLoggable
import code.views.{MapperViews, Views}
import net.liftweb.common._
import net.liftweb.json.JObject
import net.liftweb.json.JsonAST.JArray
import net.liftweb.json.JsonDSL._
import net.liftweb.util.Props

import scala.collection.immutable.{List, Set}
import scala.concurrent.Future
import scala.math.BigDecimal

/**
 * Uniquely identifies a view
 */
case class ViewIdBankIdAccountId(viewId : ViewId, bankId : BankId, accountId : AccountId) {
  override def toString = s"view $viewId, for account: $accountId at bank $bankId"
}

/*
Examples of viewId are "owner", "accountant", "auditor" etc.
They are only unique for bank and account
 */
case class ViewId(val value : String) {
  override def toString = value
}

object ViewId {
  def unapply(id : String) = Some(ViewId(id))
}

case class TransactionId(val value : String) {
  override def toString = value
}

object TransactionId {
  def unapply(id : String) = Some(TransactionId(id))
}

case class TransactionRequestType(val value : String) {
  override def toString = value
}

object TransactionRequestType {
  def unapply(id : String) = Some(TransactionRequestType(id))
}

//Note: change case class -> trait, for kafka extends it
trait TransactionRequestStatus{
  def transactionRequestId : String
  def bulkTransactionsStatus: List[TransactionStatus]
}


trait TransactionStatus{
  def transactionId : String
  def transactionStatus: String
  def transactionTimestamp: String
}

case class TransactionRequestId(val value : String) {
  override def toString = value
}

object TransactionRequestId {
  def unapply(id : String) = Some(TransactionRequestId(id))
}

case class TransactionTypeId(val value : String) {
  override def toString = value
}

object TransactionTypeId {
  def unapply(id : String) = Some(TransactionTypeId(id))
}

case class AccountId(val value : String) {
  override def toString = value
}

object AccountId {
  def unapply(id : String) = Some(AccountId(id))
}

case class BankId(val value : String) {
  override def toString = value
}

object BankId {
  def unapply(id : String) = Some(BankId(id))
}

case class AccountRoutingAddress(val value: String) {
  override def toString = value
}

object AccountRoutingAddress {
  def unapply(id: String) = Some(AccountRoutingAddress(id))
}

case class CustomerId(val value : String) {
  override def toString = value
}

object CustomerId {
  def unapply(id : String) = Some(CustomerId(id))
}


// In preparation for use in Context (api links) To replace OtherAccountId
case class CounterpartyId(val value : String) {
  override def toString = value
}

object CounterpartyId {
  def unapply(id : String) = Some(CounterpartyId(id))
}

trait Bank {
  def bankId: BankId
  def shortName : String
  def fullName : String
  def logoUrl : String
  def websiteUrl : String
  def bankRoutingScheme: String
  def bankRoutingAddress: String

  // TODO Add Group ?


  //SWIFT BIC banking code (globally unique)
  @deprecated("Please use bankRoutingScheme and bankRoutingAddress instead")
  def swiftBic: String

  //it's not entirely clear what this is/represents (BLZ in Germany?)
  @deprecated("Please use bankRoutingScheme and bankRoutingAddress instead")
  def nationalIdentifier : String
  
  //This was the behaviour in v1.2 and earlier which has since been changed
  @deprecated
  def accountv12AndBelow(user: Box[User]) : Box[List[BankAccount]] = {
    user match {
      case Full(u) => {
        Full(privateAccounts(u))
      }
      case _ => {
        Full(publicAccounts)
      }
    }
  }

  def publicAccounts : List[BankAccount] = {
    Views.views.vend.getPublicBankAccounts(this).flatMap { a =>
      BankAccount(a.bankId, a.accountId)
    }
  }

  def privateAccounts(user : User) : List[BankAccount] = {

    Views.views.vend.getPrivateBankAccounts(user, bankId).flatMap { a =>
      BankAccount(a.bankId, a.accountId)
    }

    // Note: An alternative to the above implmentation (which will call BankAccount (e.g. Kafka) once for each
    // account) - could be:
    // 1) Get the accounts / view the user should have access to:
    // Views.views.vend.getPrivateBankAccounts(user, bankId)
    // 2) Get all accounts for User
    // 3) Return just the accounts found in step 1.
    // 4) If any accounts are missing (because they belong to another user), call BankAccount for the missing accounts.
  }

  @deprecated(Helper.deprecatedJsonGenerationMessage)
  def detailedJson : JObject = {
    ("name" -> shortName) ~
    ("website" -> "") ~
    ("email" -> "")
  }

  @deprecated(Helper.deprecatedJsonGenerationMessage)
  def toJson : JObject = {
    ("alias" -> bankId.value) ~
      ("name" -> shortName) ~
      ("logo" -> "") ~
      ("links" -> linkJson)
  }

  @deprecated(Helper.deprecatedJsonGenerationMessage)
  def linkJson : JObject = {
    ("rel" -> "bank") ~
    ("href" -> {"/" + bankId + "/bank"}) ~
    ("method" -> "GET") ~
    ("title" -> {"Get information about the bank identified by " + bankId})
  }
}

object Bank {
  def apply(bankId: BankId) : Box[Bank] = {
    Connector.connector.vend.getBank(bankId)
  }

  def all(): Box[List[Bank]] = Connector.connector.vend.getBanks

  @deprecated(Helper.deprecatedJsonGenerationMessage)
  def toJson(banks: Seq[Bank]) : JArray =
    banks.map(bank => bank.toJson)

}

class AccountOwner(
  val id : String,
  val name : String
)

case class BankIdAccountId(bankId : BankId, accountId : AccountId)



/** Internal model of a Bank Account
  * @define accountType The account type aka financial product name. The customer friendly text that identifies the financial product this account is based on, as given by the bank
  * @define accountId An identifier (no spaces, url friendly, should be a UUID) that hides the actual account number (obp identifier)
  * @define number The actual bank account number as given by the bank to the customer
  * @define bankId The short bank identifier that holds this account (url friendly, usually short name of bank with hyphens)
  * @define label A string that helps identify the account to a customer or the public. Can be updated by the account owner. Default would typically include the owner display name (should be legal entity owner) + accountType + few characters of number
  * @define iban The IBAN (could be empty)
  * @define currency The currency (3 letter code)
  * @define balance The current balance on the account
  */

// TODO Add: @define productCode A code (no spaces, url friendly) that identifies the financial product this account is based on.

trait BankAccount extends MdcLoggable {

  def accountId : AccountId
  def accountType : String // (stored in the field "kind" on Mapper)
  //def productCode : String // TODO Add this shorter code.
  def balance : BigDecimal
  def currency : String
  def name : String // Is this used?
  def label : String
  @deprecated("Used the account scheme and address instead")
  def swift_bic : Option[String]   //TODO: deduplication, bank field should not be in account fields
  @deprecated("Used the account scheme and address instead")
  def iban : Option[String]
  def number : String
  def bankId : BankId
  def lastUpdate : Date
  def branchId: String
  def accountRoutingScheme: String
  def accountRoutingAddress: String
  def accountRoutings: List[AccountRouting] // Introduced in v3.0.0
  def accountRules: List[AccountRule]

  @deprecated("Get the account holder(s) via owners")
  def accountHolder : String

  //TODO: remove?
  final def bankName : String =
    Connector.connector.vend.getBank(bankId).map(_.fullName).getOrElse("")
  //TODO: remove?
  final def nationalIdentifier : String =
    Connector.connector.vend.getBank(bankId).map(_.nationalIdentifier).getOrElse("")

  //From V300, used scheme, address
  final def bankRoutingScheme : String =
    Connector.connector.vend.getBank(bankId).map(_.bankRoutingScheme).getOrElse("")
  final def bankRoutingAddress : String =
    Connector.connector.vend.getBank(bankId).map(_.bankRoutingAddress).getOrElse("")

  /*
  * Delete this account (if connector allows it, e.g. local mirror of account data)
  * */
  final def remove(user : User): Box[Boolean] = {
    if(user.hasOwnerViewAccess(BankIdAccountId(this.bankId,this.accountId))){
      Full(Connector.connector.vend.removeAccount(this.bankId, this.accountId).openOrThrowException(attemptedToOpenAnEmptyBox))
    } else {
      Failure(UserNoOwnerView+"user's email : " + user.emailAddress + ". account : " + accountId, Empty, Empty)
    }
  }

  final def updateLabel(user : User, label : String): Box[Boolean] = {
    if(user.hasOwnerViewAccess(BankIdAccountId(this.bankId,this.accountId))){
      Connector.connector.vend.updateAccountLabel(this.bankId, this.accountId, label)
    } else {
      Failure(UserNoOwnerView+"user's email : " + user.emailAddress + ". account : " + accountId, Empty, Empty)
    }
  }

  final def owners: Set[User] = {
    val accountHolders = AccountHolders.accountHolders.vend.getAccountHolders(bankId, accountId)
    if(accountHolders.isEmpty) {
      //account holders are not all set up in the db yet, so we might not get any back.
      //In this case, we just use the previous behaviour, which did not return very much information at all
      Set(new User {
        val resourceUserId = UserId(-1)
        val userId = ""
        val idGivenByProvider = ""
        val provider = ""
        val emailAddress = ""
        val name : String = accountHolder
      })
    } else {
      accountHolders
    }
  }

  private def viewNotAllowed(view : View ) = Failure(s"${UserNoPermissionAccessView} Current VIEW_ID (${view.viewId.value})")
  
 
  
  /**
  * @param user a user requesting to see the other users' permissions
  * @return a Box of all the users' permissions of this bank account if the user passed as a parameter has access to the owner view (allowed to see this kind of data)
  */
  final def permissions(user : User) : Box[List[Permission]] = {
    //check if the user have access to the owner view in this the account
    if(user.hasOwnerViewAccess(BankIdAccountId(this.bankId,this.accountId)))
      Full(Views.views.vend.permissions(BankIdAccountId(this.bankId,this.accountId)))
    else
      Failure("user " + user.emailAddress + " does not have access to owner view on account " + accountId, Empty, Empty)
  }

  /**
  * @param user the user requesting to see the other users permissions on this account
  * @param otherUserProvider the authentication provider of the user whose permissions will be retrieved
  * @param otherUserIdGivenByProvider the id of the user (the one given by their auth provider) whose permissions will be retrieved
  * @return a Box of the user permissions of this bank account if the user passed as a parameter has access to the owner view (allowed to see this kind of data)
  */
  final def permission(user : User, otherUserProvider : String, otherUserIdGivenByProvider: String) : Box[Permission] = {
    //check if the user have access to the owner view in this the account
    if(user.hasOwnerViewAccess(BankIdAccountId(this.bankId,this.accountId)))
      for{
        u <- User.findByProviderId(otherUserProvider, otherUserIdGivenByProvider)
        p <- Views.views.vend.permission(BankIdAccountId(this.bankId,this.accountId), u)
        } yield p
    else
      Failure(UserNoOwnerView+"user's email : " + user.emailAddress + ". account : " + accountId, Empty, Empty)
  }

  /**
  * @param user the user that wants to grant another user access to a view on this account
  * @param viewUID uid of the view to which we want to grant access
  * @param otherUserProvider the authentication provider of the user to whom access to the view will be granted
  * @param otherUserIdGivenByProvider the id of the user (the one given by their auth provider) to whom access to the view will be granted
  * @return a Full(true) if everything is okay, a Failure otherwise
  */
  final def addPermission(user : User, viewUID : ViewIdBankIdAccountId, otherUserProvider : String, otherUserIdGivenByProvider: String) : Box[View] = {
    //check if the user have access to the owner view in this the account
    if(user.hasOwnerViewAccess(BankIdAccountId(this.bankId,this.accountId)))
      for{
        otherUser <- User.findByProviderId(otherUserProvider, otherUserIdGivenByProvider) //check if the userId corresponds to a user
        savedView <- Views.views.vend.addPermission(viewUID, otherUser) ?~ "could not save the privilege"
      } yield savedView
    else
      Failure(UserNoOwnerView+"user's email : " + user.emailAddress + ". account : " + accountId, Empty, Empty)
  }

  /**
  * @param user the user that wants to grant another user access to a several views on this account
  * @param viewUIDs uids of the views to which we want to grant access
  * @param otherUserProvider the authentication provider of the user to whom access to the views will be granted
  * @param otherUserIdGivenByProvider the id of the user (the one given by their auth provider) to whom access to the views will be granted
  * @return a the list of the granted views if everything is okay, a Failure otherwise
  */
  final def addPermissions(user : User, viewUIDs : List[ViewIdBankIdAccountId], otherUserProvider : String, otherUserIdGivenByProvider: String) : Box[List[View]] = {
    //check if the user have access to the owner view in this the account
    if(user.hasOwnerViewAccess(BankIdAccountId(this.bankId,this.accountId)))
      for{
        otherUser <- User.findByProviderId(otherUserProvider, otherUserIdGivenByProvider) //check if the userId corresponds to a user
        grantedViews <- Views.views.vend.addPermissions(viewUIDs, otherUser) ?~ "could not save the privilege"
      } yield grantedViews
    else
      Failure(UserNoOwnerView+"user's email : " + user.emailAddress + ". account : " + accountId, Empty, Empty)
  }

  /**
  * @param user the user that wants to revoke another user's access to a view on this account
  * @param viewUID uid of the view to which we want to revoke access
  * @param otherUserProvider the authentication provider of the user to whom access to the view will be revoked
  * @param otherUserIdGivenByProvider the id of the user (the one given by their auth provider) to whom access to the view will be revoked
  * @return a Full(true) if everything is okay, a Failure otherwise
  */
  final def revokePermission(user : User, viewUID : ViewIdBankIdAccountId, otherUserProvider : String, otherUserIdGivenByProvider: String) : Box[Boolean] = {
    //check if the user have access to the owner view in this the account
    if(user.hasOwnerViewAccess(BankIdAccountId(this.bankId,this.accountId)))
      for{
        otherUser <- User.findByProviderId(otherUserProvider, otherUserIdGivenByProvider) //check if the userId corresponds to a user
        isRevoked <- Views.views.vend.revokePermission(viewUID, otherUser) ?~ "could not revoke the privilege"
      } yield isRevoked
    else
      Failure(UserNoOwnerView+"user's email : " + user.emailAddress + ". account : " + accountId, Empty, Empty)
  }

  /**
  *
  * @param user the user that wants to revoke another user's access to all views on this account
  * @param otherUserProvider the authentication provider of the user to whom access to all views will be revoked
  * @param otherUserIdGivenByProvider the id of the user (the one given by their auth provider) to whom access to all views will be revoked
  * @return a Full(true) if everything is okay, a Failure otherwise
  */

  final def revokeAllPermissions(user : User, otherUserProvider : String, otherUserIdGivenByProvider: String) : Box[Boolean] = {
    //check if the user have access to the owner view in this the account
    if(user.hasOwnerViewAccess(BankIdAccountId(this.bankId,this.accountId)))
      for{
        otherUser <- User.findByProviderId(otherUserProvider, otherUserIdGivenByProvider) //check if the userId corresponds to a user
        isRevoked <- Views.views.vend.revokeAllPermissions(bankId, accountId, otherUser)
      } yield isRevoked
    else
      Failure(UserNoOwnerView+"user's email : " + user.emailAddress + ". account : " + accountId, Empty, Empty)
  }


  final def createView(userDoingTheCreate : User,v: CreateViewJson): Box[View] = {
    if(!userDoingTheCreate.hasOwnerViewAccess(BankIdAccountId(this.bankId,this.accountId))) {
      Failure({"user: " + userDoingTheCreate.idGivenByProvider + " at provider " + userDoingTheCreate.provider + " does not have owner access"})
    } else {
      val view = Views.views.vend.createView(BankIdAccountId(this.bankId,this.accountId), v)

      //if(view.isDefined) {
      //  logger.debug("user: " + userDoingTheCreate.idGivenByProvider + " at provider " + userDoingTheCreate.provider + " created view: " + view.get +
      //      " for account " + accountId + "at bank " + bankId)
      //}

      view
    }
  }

  final def updateView(userDoingTheUpdate : User, viewId : ViewId, v: UpdateViewJSON) : Box[View] = {
    if(!userDoingTheUpdate.hasOwnerViewAccess(BankIdAccountId(this.bankId,this.accountId))) {
      Failure({"user: " + userDoingTheUpdate.idGivenByProvider + " at provider " + userDoingTheUpdate.provider + " does not have owner access"})
    } else {
      val view = Views.views.vend.updateView(BankIdAccountId(this.bankId,this.accountId), viewId, v)

      //if(view.isDefined) {
      //  logger.debug("user: " + userDoingTheUpdate.idGivenByProvider + " at provider " + userDoingTheUpdate.provider + " updated view: " + view.get +
      //      " for account " + accountId + "at bank " + bankId)
      //}

      view
    }
  }

  final def removeView(userDoingTheRemove : User, viewId: ViewId) : Box[Unit] = {
    if(!userDoingTheRemove.hasOwnerViewAccess(BankIdAccountId(this.bankId,this.accountId))) {
      return Failure({"user: " + userDoingTheRemove.idGivenByProvider + " at provider " + userDoingTheRemove.provider + " does not have owner access"})
    } else {
      val deleted = Views.views.vend.removeView(viewId, BankIdAccountId(this.bankId,this.accountId))

      //if (deleted.isDefined) {
      //    logger.debug("user: " + userDoingTheRemove.idGivenByProvider + " at provider " + userDoingTheRemove.provider + " deleted view: " + viewId +
      //    " for account " + accountId + "at bank " + bankId)
      //}

      deleted
    }
  }

  final def moderatedTransaction(transactionId: TransactionId, view: View, user: Box[User]) : Box[ModeratedTransaction] = {
    if(APIUtil.hasAccess(view, user))
      Connector.connector.vend.getTransaction(bankId, accountId, transactionId).flatMap(view.moderate)
    else
      viewNotAllowed(view)
  }

  /*
   end views
  */

  // TODO We should extract params (and their defaults) prior to this call, so this whole function can be cached.
  final def getModeratedTransactions(user : Box[User], view : View, queryParams: OBPQueryParam*)(session: Option[CallContext]): Box[List[ModeratedTransaction]] = {
    if(APIUtil.hasAccess(view, user)) {
      for {
        transactions <- Connector.connector.vend.getTransactions(bankId, accountId, session, queryParams: _*)
        moderated <- view.moderateTransactionsWithSameAccount(transactions) ?~! "Server error"
      } yield moderated
    }
    else viewNotAllowed(view)
  }
  
  // TODO We should extract params (and their defaults) prior to this call, so this whole function can be cached.
  final def getModeratedTransactionsCore(user : Box[User], view : View, queryParams: OBPQueryParam*)(session: Option[CallContext]): Box[List[ModeratedTransactionCore]] = {
    if(APIUtil.hasAccess(view, user)) {
      for {
        transactions <- Connector.connector.vend.getTransactionsCore(bankId, accountId, session, queryParams: _*)
        moderated <- view.moderateTransactionsWithSameAccountCore(transactions) ?~! "Server error"
      } yield moderated
    }
    else viewNotAllowed(view)
  }

  final def moderatedBankAccount(view: View, user: Box[User]) : Box[ModeratedBankAccount] = {
    if(APIUtil.hasAccess(view, user))
      //implicit conversion from option to box
      view.moderate(this)
    else
      viewNotAllowed(view)
  }

  /**
  * @param the view that we will use to get the ModeratedOtherBankAccount list
  * @param the user that want access to the ModeratedOtherBankAccount list
  * @return a Box of a list ModeratedOtherBankAccounts, it the bank
  *  accounts that have at least one transaction in common with this bank account
  */
  final def moderatedOtherBankAccounts(view : View, user : Box[User]) : Box[List[ModeratedOtherBankAccount]] =
    if(APIUtil.hasAccess(view, user))
      Full(Connector.connector.vend.getCounterpartiesFromTransaction(bankId, accountId).openOrThrowException(attemptedToOpenAnEmptyBox).map(oAcc => view.moderate(oAcc)).flatten)
    else
      viewNotAllowed(view)
  /**
  * @param the ID of the other bank account that the user want have access
  * @param the view that we will use to get the ModeratedOtherBankAccount
  * @param the user that want access to the otherBankAccounts list
  * @return a Box of a ModeratedOtherBankAccounts, it a bank
  *  account that have at least one transaction in common with this bank account
  */
  final def moderatedOtherBankAccount(counterpartyID : String, view : View, user : Box[User]) : Box[ModeratedOtherBankAccount] =
    if(APIUtil.hasAccess(view, user))
      Connector.connector.vend.getCounterpartyFromTransaction(bankId, accountId, counterpartyID).flatMap(oAcc => view.moderate(oAcc))
    else
      viewNotAllowed(view)

}

object BankAccount {
  def apply(bankId: BankId, accountId: AccountId) : Box[BankAccount] = {
    Connector.connector.vend.getBankAccount(bankId, accountId)
  }

  def apply(bankId: BankId, accountId: AccountId, callContext: Option[CallContext]) : Box[BankAccount] = {
    Connector.connector.vend.getBankAccount(bankId, accountId, callContext)
  }
  /**
    * Mapping a CounterpartyTrait to OBP BankAccount.
    * If connector=mapped, we will search for the obp BankAccount.
    * If connector=kafka, we can not find a bankAccount in obp, we only map some fileds. It depends on what we get from Adapter side.
    *       
    * @param counterparty 
    * @return BankAccount
    */
  def toBankAccount(counterparty: CounterpartyTrait) : Box[BankAccount] = {
    if (APIUtil.isSandboxMode)
      for{
        toBankId <- Full(BankId(counterparty.otherBankRoutingAddress))
        toAccountId <- Full(AccountId(counterparty.otherAccountRoutingAddress))
        toAccount <- BankAccount(toBankId, toAccountId) ?~! s"${ErrorMessages.CounterpartyNotFound} Now $toBankId and $toAccountId, please use correct OBP BankAccount to create the Counterparty. "
      } yield{
        toAccount
      }
    else
      Full(
        BankAccountInMemory(
  
          //Map Counterparty <--> BankAccount, not all fields we can fill.
          accountHolder = counterparty.name,
          accountRoutingScheme = counterparty.otherAccountRoutingScheme,
          accountRoutingAddress = counterparty.otherAccountRoutingAddress,
          accountRoutings = List(
            AccountRouting(counterparty.otherAccountRoutingScheme,
                           counterparty.otherAccountRoutingAddress),
            AccountRouting(counterparty.otherAccountSecondaryRoutingScheme,
                           counterparty.otherAccountSecondaryRoutingAddress)
          ),
  
  
          //Can not get from counterparty
          bankId = BankId(""),
          accountId = AccountId(""),
          accountType = null,
          balance = 0,
          currency = "EUR",
          lastUpdate = null,
          name = "",
          label = "",
          branchId = "",
          swift_bic = Option(""),
          iban = Option(""),
          number = "",
          accountRules = Nil
        )
      )
  }
  
  def publicAccounts : List[BankAccount] = {
    Views.views.vend.getAllPublicAccounts.flatMap { a =>
      BankAccount(a.bankId, a.accountId)
    }
  }

  def privateAccounts(user : User) : List[BankAccount] = {
    Views.views.vend.getPrivateBankAccounts(user).flatMap { a =>
      BankAccount(a.bankId, a.accountId)
    }
  }
}

//This class is used for propagate the BankAccount as the parameters over different methods.
case class BankAccountInMemory(
  //BankAccount Trait
  bankId: BankId,
  accountId: AccountId,
  accountType: String,
  balance: BigDecimal,
  currency: String,
  name: String,
  lastUpdate: Date,
  accountHolder: String,
  label: String,
  accountRoutingScheme: String,
  accountRoutingAddress: String,
  branchId: String,
  swift_bic: Option[String],
  iban: Option[String],
  number: String,
  accountRoutings: List[AccountRouting],
  accountRules: List[AccountRule]
) extends BankAccount 

/*
The other bank account or counterparty in a transaction
as see from the perspective of the original party.
 */


// Note: See also CounterpartyTrait
case class Counterparty(
  
  @deprecated("older version, please first consider the V210, account scheme and address","05/05/2017")
  val nationalIdentifier: String, // This is the scheme a consumer would use to instruct a payment e.g. IBAN
  val kind: String, // Type of bank account.
  
  // The following fields started from V210
  val counterpartyId: String,
  val counterpartyName: String,
  val thisBankId: BankId, // i.e. the Account that sends/receives money to/from this Counterparty
  val thisAccountId: AccountId, // These 2 fields specify the account that uses this Counterparty
  val otherBankRoutingScheme: String, // This is the scheme a consumer would use to specify the bank e.g. BIC
  val otherBankRoutingAddress: Option[String], // The (BIC) value e.g. 67895
  val otherAccountRoutingScheme: String, // This is the scheme a consumer would use to instruct a payment e.g. IBAN
  val otherAccountRoutingAddress: Option[String], // The (IBAN) value e.g. 2349870987820374
  val otherAccountProvider: String, // hasBankId and hasAccountId would refer to an OBP account
  val isBeneficiary: Boolean // True if the originAccount can send money to the Counterparty
) {
  val metadata: CounterpartyMetadata = Counterparties.counterparties.vend.getOrCreateMetadata(
    thisBankId, 
    thisAccountId, 
    counterpartyId, 
    counterpartyName
  ).openOrThrowException("Can not getOrCreateMetadata !")
}

case class CounterpartyCore(
   kind:String,
   counterpartyId: String,
   counterpartyName: String,
   thisBankId: BankId, // i.e. the Account that sends/receives money to/from this Counterparty
   thisAccountId: AccountId, // These 2 fields specify the account that uses this Counterparty
   otherBankRoutingScheme: String, // This is the scheme a consumer would use to specify the bank e.g. BIC
   otherBankRoutingAddress: Option[String], // The (BIC) value e.g. 67895
   otherAccountRoutingScheme: String, // This is the scheme a consumer would use to instruct a payment e.g. IBAN
   otherAccountRoutingAddress: Option[String], // The (IBAN) value e.g. 2349870987820374
   otherAccountProvider: String, // hasBankId and hasAccountId would refer to an OBP account
   isBeneficiary: Boolean // True if the originAccount can send money to the Counterparty
)
trait TransactionUUID {
  def theTransactionId : TransactionId
  def theBankId : BankId
  def theAccountId : AccountId
}

class Transaction(
                   //A universally unique id
                   val uuid: String,
                   //id is unique for transactions of @thisAccount
                   val id : TransactionId,
                   val thisAccount : BankAccount,
                   val otherAccount : Counterparty,
                   //E.g. cash withdrawal, electronic payment, etc.
                   val transactionType : String,
                   val amount : BigDecimal,
                   //ISO 4217, e.g. EUR, GBP, USD, etc.
                   val currency : String,
                   // Bank provided label
                   val description : Option[String],
                   // The date the transaction was initiated
                   val startDate : Date,
                   // The date when the money finished changing hands
                   val finishDate : Date,
                   //the new balance for the bank account
                   val balance :  BigDecimal
) {

  val bankId = thisAccount.bankId
  val accountId = thisAccount.accountId

  /**
   * The metadata is set up using dependency injection. If you want to, e.g. override the Comments implementation
   * for a particular scope, use Comments.comments.doWith(NewCommentsImplementation extends Comments{}){
   *   //code in here will use NewCommentsImplementation (e.g. val t = new Transaction(...) will result in Comments.comments.vend
   *   // return NewCommentsImplementation here below)
   * }
   *
   * If you want to change the current default implementation, you would change the buildOne function in Comments to
   * return a different value
   *
   */
  val metadata : TransactionMetadata = new TransactionMetadata(
      Narrative.narrative.vend.getNarrative(bankId, accountId, id) _,
      Narrative.narrative.vend.setNarrative(bankId, accountId, id) _,
      Comments.comments.vend.getComments(bankId, accountId, id) _,
      Comments.comments.vend.addComment(bankId, accountId, id) _,
      Comments.comments.vend.deleteComment(bankId, accountId, id) _,
      Tags.tags.vend.getTags(bankId, accountId, id) _,
      Tags.tags.vend.addTag(bankId, accountId, id) _,
      Tags.tags.vend.deleteTag(bankId, accountId, id) _,
      TransactionImages.transactionImages.vend.getImagesForTransaction(bankId, accountId, id) _,
      TransactionImages.transactionImages.vend.addTransactionImage(bankId, accountId, id) _,
      TransactionImages.transactionImages.vend.deleteTransactionImage(bankId, accountId, id) _,
      WhereTags.whereTags.vend.getWhereTagForTransaction(bankId, accountId, id) _,
      WhereTags.whereTags.vend.addWhereTag(bankId, accountId, id) _,
      WhereTags.whereTags.vend.deleteWhereTag(bankId, accountId, id) _
    )
}

case class TransactionCore(
  id: TransactionId,
  thisAccount: BankAccount,
  otherAccount: CounterpartyCore,
  transactionType: String,
  amount: BigDecimal,
  currency: String,
  description: Option[String],
  startDate: Date,
  finishDate: Date,
  balance: BigDecimal
)

case class AmountOfMoney (
  val currency: String,
  val amount: String
)

case class Iban(
  val iban: String
)

case class AccountRouting(
  scheme: String,
  address: String
)

case class CoreAccount(
  id: String,
  label: String,
  bank_id: String,
  account_routing: AccountRouting
)

case class AccountHeld(
  id: String,
  bank_id: String,
  number: String,
  account_routing: AccountRouting
)

case class CounterpartyBespoke(
  key: String,
  value: String
)