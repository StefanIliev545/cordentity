package com.luxoft.blockchainlab.corda.hyperledger.indy.flow

import co.paralleluniverse.fibers.Suspendable
import com.luxoft.blockchainlab.corda.hyperledger.indy.contract.IndyCredentialDefinitionContract
import com.luxoft.blockchainlab.corda.hyperledger.indy.contract.IndyRevocationRegistryContract
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.IndyRevocationRegistryDefinition
import com.luxoft.blockchainlab.corda.hyperledger.indy.data.state.getCredentialDefinitionById
import com.luxoft.blockchainlab.hyperledger.indy.IndyCredentialDefinitionNotFoundException
import com.luxoft.blockchainlab.hyperledger.indy.models.CredentialDefinitionId
import com.luxoft.blockchainlab.hyperledger.indy.models.RevocationRegistryInfo
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndContract
import net.corda.core.flows.*
import net.corda.core.transactions.TransactionBuilder

/**
 * Create revocation registry for some credential definition
 */
object CreateRevocationRegistryFlow {

    /**
     * @param credentialDefinitionId [CredentialDefinitionId]
     * @param credentialLimit [Int] - Maximum number of possible credentials issued per definition
     */
    @InitiatingFlow
    @StartableByRPC
    class Authority(
        private val credentialDefinitionId: CredentialDefinitionId,
        private val credentialLimit: Int
    ) : FlowLogic<RevocationRegistryInfo>() {

        @Suspendable
        override fun call(): RevocationRegistryInfo {
            try {
                val revocationRegistryInfo = indyUser().createRevocationRegistryAndStoreOnLedger(
                    credentialDefinitionId,
                    credentialLimit
                )

                val signers = listOf(ourIdentity.owningKey)

                // create new revocation registry definition state
                val revocationRegistryDefinition = IndyRevocationRegistryDefinition(
                    revocationRegistryInfo.definition.getRevocationRegistryIdObject()!!,
                    credentialDefinitionId,
                    listOf(ourIdentity),
                    credentialLimit,
                    0
                )
                val revocationRegistryDefinitionOut = StateAndContract(
                    revocationRegistryDefinition,
                    IndyRevocationRegistryContract::class.java.name
                )
                val revocationRegistryCmdType = IndyRevocationRegistryContract.Command.Create()
                val revocationRegistryCmd = Command(revocationRegistryCmdType, signers)

                // consume old credential definition state
                val credentialDefinitionIn = getCredentialDefinitionById(credentialDefinitionId)
                    ?: throw IndyCredentialDefinitionNotFoundException(
                        credentialDefinitionId,
                        "Corda does't have proper schema in vault"
                    )

                val credentialDefinitionOut = StateAndContract(
                    credentialDefinitionIn.state.data,
                    IndyCredentialDefinitionContract::class.java.name
                )
                val credentialDefinitionCmdType = IndyCredentialDefinitionContract.Command.Consume()
                val credentialDefinitionCmd = Command(credentialDefinitionCmdType, signers)

                // submit txn
                val trxBuilder = TransactionBuilder(whoIsNotary())
                    .withItems(
                        credentialDefinitionIn,
                        credentialDefinitionOut,
                        credentialDefinitionCmd,
                        revocationRegistryDefinitionOut,
                        revocationRegistryCmd
                    )

                trxBuilder.toWireTransaction(serviceHub)
                    .toLedgerTransaction(serviceHub)
                    .verify()

                val selfSignedTx = serviceHub.signInitialTransaction(trxBuilder, ourIdentity.owningKey)

                subFlow(FinalityFlow(selfSignedTx))

                return revocationRegistryInfo
            } catch (t: Throwable) {
                logger.error("Unable to create revocation registry", t)
                throw FlowException(t.message)
            }
        }

    }
}
