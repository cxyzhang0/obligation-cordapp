package net.corda.examples.obligation.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.confidential.IdentitySyncFlow
import net.corda.confidential.SwapIdentitiesFlow
import net.corda.core.contracts.Command
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.*
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.Party
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.transactions.WireTransaction
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.unwrap
import net.corda.examples.obligation.Obligation
import net.corda.examples.obligation.ObligationContract
import net.corda.examples.obligation.ObligationContract.Companion.OBLIGATION_CONTRACT_ID

object TransferObligation {

    @StartableByRPC
    @InitiatingFlow
    class Initiator(private val linearId: UniqueIdentifier,
                    private val newLender: Party,
                    private val anonymous: Boolean = true) : ObligationBaseFlow() {

        override val progressTracker: ProgressTracker = tracker()

        companion object {
            object GET_OBLIGATION : ProgressTracker.Step("Obtaining obligation from vault.")
            object CHECK_INITIATOR : ProgressTracker.Step("Checking current lender is initating flow.")
            object BUILD_TRANSACTION : ProgressTracker.Step("Building and verifying transaction.")
            object SIGN_TRANSACTION : ProgressTracker.Step("Signing transaction.")
            object SYNC_OUR_IDENTITY : ProgressTracker.Step("Syncing our identity with the counterparties.") {
                override fun childProgressTracker() = IdentitySyncFlow.Send.tracker()
            }
            object COLLECT_SIGS : ProgressTracker.Step("Collecting counterparty signatures.") {
                override fun childProgressTracker() = CollectSignaturesFlow.tracker()
            }
            object FINALISE : ProgressTracker.Step("Finalising transaction.") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(GET_OBLIGATION, CHECK_INITIATOR, BUILD_TRANSACTION, SIGN_TRANSACTION, SYNC_OUR_IDENTITY, COLLECT_SIGS, FINALISE)
        }

        @Suspendable
        override fun call(): SignedTransaction {
            // Stage 1. Retrieve obligation with the correct linear ID from the vault.
            progressTracker.currentStep = GET_OBLIGATION
            val obligationToTransfer = getObligationByLinearId(linearId)
            val inputObligation = obligationToTransfer.state.data

            val borrower = getBorrowerIdentity(inputObligation)
            // We call `toSet` in case the borrower and the new lender are the same party.
            val sessions = listOf(borrower, newLender).toSet().map { party -> initiateFlow(party) }.toSet()

            // Stage 2. This flow can only be initiated by the current lender. Abort if the borrower started this flow.
            progressTracker.currentStep = CHECK_INITIATOR
            check(ourIdentity == getLenderIdentity(inputObligation)) { "Obligation transfer can only be initiated by the lender." }

            // Stage 3. Create the new obligation state reflecting a new lender.
            progressTracker.currentStep = BUILD_TRANSACTION
            val transferredObligation = createOutputObligation(inputObligation)

            // Stage 4. Create the transfer command.
            val signers = inputObligation.participants + transferredObligation.lender
            val signerKeys = signers.map { it.owningKey }
            val transferCommand = Command(ObligationContract.Commands.Transfer(), signerKeys)

            // Stage 5. Create a transaction builder, add the states and commands, and verify the output.
            val builder = TransactionBuilder(firstNotary)
                    .addInputState(obligationToTransfer)
                    .addOutputState(transferredObligation, OBLIGATION_CONTRACT_ID)
                    .addCommand(transferCommand)
            builder.verify(serviceHub)

            // Stage 6. Sign the transaction using the key we originally used.
            progressTracker.currentStep = SIGN_TRANSACTION
            val ptx = serviceHub.signInitialTransaction(builder, inputObligation.lender.owningKey)

            // Stage 7. Share our anonymous identity with the borrower and the new lender.
            progressTracker.currentStep = SYNC_OUR_IDENTITY
            subFlow(IdentitySyncFlow.Send(sessions, ptx.tx, SYNC_OUR_IDENTITY.childProgressTracker()))

            // Stage 8. Collect signatures from the borrower and the new lender.
            progressTracker.currentStep = COLLECT_SIGS
            val stx = subFlow(CollectSignaturesFlow(
                    ptx, sessions, listOf(inputObligation.lender.owningKey), COLLECT_SIGS.childProgressTracker()))

            // Stage 9. Tell the counterparties about each other so they can sync confidential identities.
            sessions.forEach { session ->
                if (session.counterparty == borrower) session.send(newLender)
                else session.send(borrower)
            }

            // Stage 10. Notarise and record the transaction in our vaults.
            progressTracker.currentStep = FINALISE
            return subFlow(FinalityFlow(stx))
        }

        @Suspendable
        private fun getLenderIdentity(inputObligation: Obligation): AbstractParty {
            return if (inputObligation.lender is AnonymousParty) {
                resolveIdentity(inputObligation.lender)
            } else {
                inputObligation.lender
            }
        }

        @Suspendable
        private fun createOutputObligation(inputObligation: Obligation): Obligation {
            return if (anonymous) {
                val txKeys = subFlow(SwapIdentitiesFlow(newLender))
                val anonymousLender = txKeys[newLender] ?: throw FlowException("Couldn't get lender's conf. identity.")
                inputObligation.withNewLender(anonymousLender)
            } else {
                inputObligation.withNewLender(newLender)
            }
        }

        @Suspendable
        private fun getBorrowerIdentity(inputObligation: Obligation): Party {
            return if (inputObligation.borrower is AnonymousParty) {
                resolveIdentity(inputObligation.borrower)
            } else {
                inputObligation.borrower as Party
            }
        }
    }

    @InitiatedBy(Initiator::class)
    class Responder(private val otherFlow: FlowSession) : FlowLogic<SignedTransaction>() {
        override val progressTracker: ProgressTracker = tracker()

        companion object {
            object SYNC_FIRST_IDENTITY : ProgressTracker.Step("Syncing our identity with the current lender.") {
                override fun childProgressTracker() = IdentitySyncFlow.Send.tracker()
            }
            object SIGN_TRANSACTION : ProgressTracker.Step("Signing transaction.")
            object SYNC_SECOND_IDENTITY : ProgressTracker.Step("Syncing our identity with the other counterparty.") {
                override fun childProgressTracker() = IdentitySyncFlow.Send.tracker()
            }

            fun tracker() = ProgressTracker(SYNC_FIRST_IDENTITY, SIGN_TRANSACTION, SYNC_SECOND_IDENTITY)
        }


        @Suspendable
        override fun call(): SignedTransaction {
            // Stage 1. Sync identities with the current lender.
            progressTracker.currentStep = SYNC_FIRST_IDENTITY
            subFlow(IdentitySyncFlow.Receive(otherFlow))

            // Stage 2. Sign the transaction.
            progressTracker.currentStep = SIGN_TRANSACTION
            val stx = subFlow(SignTxFlowNoChecking(otherFlow))

            // Stage 3. Sync identities with the other counterparty.
            progressTracker.currentStep = SYNC_SECOND_IDENTITY
            val otherParty = otherFlow.receive<Party>().unwrap { it }
            subFlow(IdentitySyncFlowWrapper.Initiator(otherParty, stx.tx, SYNC_SECOND_IDENTITY.childProgressTracker()))

            return waitForLedgerCommit(stx.id)
        }
    }
}