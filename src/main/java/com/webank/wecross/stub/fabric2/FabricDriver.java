package com.webank.wecross.stub.fabric2;

import static com.webank.wecross.stub.fabric2.utils.FabricUtils.bytesToLong;
import static com.webank.wecross.stub.fabric2.utils.FabricUtils.longToBytes;

import com.google.protobuf.ByteString;
import com.webank.wecross.stub.*;
import com.webank.wecross.stub.fabric2.FabricCustomCommand.ApproveChaincodeRequest;
import com.webank.wecross.stub.fabric2.FabricCustomCommand.CommitChaincodeRequest;
import com.webank.wecross.stub.fabric2.FabricCustomCommand.InstallChaincodeRequest;
import com.webank.wecross.stub.fabric2.FabricCustomCommand.InstallCommand;
import com.webank.wecross.stub.fabric2.FabricCustomCommand.InstantiateChaincodeRequest;
import com.webank.wecross.stub.fabric2.FabricCustomCommand.InstantiateCommand;
import com.webank.wecross.stub.fabric2.FabricCustomCommand.QueryCommittedRequest;
import com.webank.wecross.stub.fabric2.FabricCustomCommand.UpgradeChaincodeRequest;
import com.webank.wecross.stub.fabric2.FabricCustomCommand.UpgradeCommand;
import com.webank.wecross.stub.fabric2.account.FabricAccount;
import com.webank.wecross.stub.fabric2.account.FabricAccountFactory;
import com.webank.wecross.stub.fabric2.common.FabricType;
import com.webank.wecross.stub.fabric2.proxy.ProxyChaincodeResource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.hyperledger.fabric.protos.peer.lifecycle.Lifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FabricDriver implements Driver {
    private Logger logger = LoggerFactory.getLogger(FabricDriver.class);

    public byte[] encodeTransactionRequest(
            TransactionContext transactionContext, TransactionRequest transactionRequest) {
        try {
            byte[] data = EndorserRequestFactory.encode(transactionContext, transactionRequest);

            TransactionParams transactionParams =
                    new TransactionParams(transactionRequest, data, false);

            return transactionParams.toBytes();
        } catch (Exception e) {
            logger.error("encodeTransactionRequest error: ", e);
            return null;
        }
    }

    @Override
    public ImmutablePair<Boolean, TransactionRequest> decodeTransactionRequest(Request request) {

        int requestType = request.getType();
        if ((requestType != FabricType.ConnectionMessage.FABRIC_CALL)
                && (requestType != FabricType.ConnectionMessage.FABRIC_SENDTRANSACTION_ENDORSER)) {
            return new ImmutablePair<>(false, null);
        }

        try {
            TransactionParams transactionParams = TransactionParams.parseFrom(request.getData());
            TransactionRequest plainRequest = transactionParams.getOriginTransactionRequest();

            TransactionRequest transactionRequest =
                    EndorserRequestFactory.decode(transactionParams.getData());

            if (!transactionParams.isByProxy()) {
                // check the same
                if (!transactionRequest.getMethod().equals(plainRequest.getMethod())
                        || !Arrays.equals(transactionRequest.getArgs(), plainRequest.getArgs())) {
                    throw new Exception(
                            "Illegal transaction request bytes, recover: "
                                    + transactionRequest
                                    + " plain: "
                                    + plainRequest);
                }

            } else {
                // TODO: Verify proxy transaction
            }

            if (logger.isDebugEnabled()) {
                logger.debug(
                        " plainRequest: {}, transactionRequest: {}",
                        plainRequest,
                        transactionRequest);
            }

            return new ImmutablePair<>(true, plainRequest);
        } catch (Exception e) {
            logger.error("decodeTransactionRequest error: ", e);
            return new ImmutablePair<>(true, null);
        }
    }

    @Override
    public List<ResourceInfo> getResources(Connection connection) {
        if (connection instanceof FabricConnection) {
            return ((FabricConnection) connection).getResources();
        }

        logger.warn(" Not fabric connection, name: {}", connection.getClass().getName());
        return new ArrayList<>();
    }

    public byte[] encodeTransactionResponse(TransactionResponse response) {

        switch (response.getResult().length) {
            case 0:
                return new byte[] {};
            case 1:
                String result = response.getResult()[0];
                ByteString payload = ByteString.copyFrom(result, StandardCharsets.UTF_8);
                return payload.toByteArray();
            default:
                logger.error(
                        "encodeTransactionResponse error: Illegal result size: {}",
                        response.getResult().length);
                return null;
        }
    }

    public TransactionResponse decodeTransactionResponse(byte[] data) {
        // Fabric only has 1 return object
        ByteString payload = ByteString.copyFrom(data);
        String[] result = new String[] {payload.toStringUtf8()};

        TransactionResponse response = new TransactionResponse();
        response.setResult(result);
        return response;
    }

    @Override
    public void asyncCall(
            TransactionContext context,
            TransactionRequest request,
            boolean byProxy,
            Connection connection,
            Callback callback) {
        if (byProxy) {
            asyncCallByProxy(context, request, connection, callback);
        } else {
            asyncCall(context, request, connection, callback);
        }
    }

    private void asyncCall(
            TransactionContext transactionContext,
            TransactionRequest transactionRequest,
            Connection connection,
            Driver.Callback callback) {

        try {
            // check
            checkRequest(transactionContext, transactionRequest);

            byte[] data =
                    EndorserRequestFactory.buildProposalRequestBytes(
                            transactionContext, transactionRequest);
            TransactionParams transactionParams =
                    new TransactionParams(transactionRequest, data, false);

            Request endorserRequest = new Request();
            endorserRequest.setData(transactionParams.toBytes());
            endorserRequest.setType(FabricType.ConnectionMessage.FABRIC_CALL);
            endorserRequest.setResourceInfo(transactionContext.getResourceInfo());

            connection.asyncSend(
                    endorserRequest,
                    connectionResponse -> {
                        TransactionResponse response = new TransactionResponse();
                        TransactionException transactionException;
                        try {
                            if (connectionResponse.getErrorCode()
                                    == FabricType.TransactionResponseStatus.SUCCESS) {
                                response = decodeTransactionResponse(connectionResponse.getData());
                                response.setHash(
                                        EndorserRequestFactory.getTxIDFromEnvelopeBytes(data));
                            }
                            transactionException =
                                    new TransactionException(
                                            connectionResponse.getErrorCode(),
                                            connectionResponse.getErrorMessage());
                        } catch (Exception e) {
                            String errorMessage = "Fabric driver call onResponse exception: " + e;
                            logger.error(errorMessage);
                            transactionException =
                                    TransactionException.Builder.newInternalException(errorMessage);
                        }
                        callback.onTransactionResponse(transactionException, response);
                    });

        } catch (Exception e) {
            String errorMessage = "Fabric driver call exception: " + e;
            logger.error(errorMessage);
            TransactionResponse response = new TransactionResponse();
            TransactionException transactionException =
                    TransactionException.Builder.newInternalException(errorMessage);
            callback.onTransactionResponse(transactionException, response);
        }
    }

    private void asyncCallByProxy(
            TransactionContext transactionContext,
            TransactionRequest transactionRequest,
            Connection connection,
            Callback callback) {

        try {
            checkProxyRequest(transactionContext, transactionRequest);

            ImmutablePair<TransactionContext, TransactionRequest> proxyRequest =
                    ProxyChaincodeResource.toProxyRequest(
                            transactionContext,
                            transactionRequest,
                            ProxyChaincodeResource.MethodType.CALL);

            byte[] data =
                    EndorserRequestFactory.buildProposalRequestBytes(
                            proxyRequest.getLeft(), proxyRequest.getRight());
            TransactionParams transactionParams =
                    new TransactionParams(transactionRequest, data, true);

            Request endorserRequest = new Request();
            endorserRequest.setData(transactionParams.toBytes());
            endorserRequest.setType(FabricType.ConnectionMessage.FABRIC_CALL);
            endorserRequest.setResourceInfo(transactionContext.getResourceInfo());

            connection.asyncSend(
                    endorserRequest,
                    connectionResponse -> {
                        TransactionResponse response = new TransactionResponse();
                        TransactionException transactionException;
                        try {
                            if (connectionResponse.getErrorCode()
                                    == FabricType.TransactionResponseStatus.SUCCESS) {
                                response = decodeTransactionResponse(connectionResponse.getData());
                                response.setHash(
                                        EndorserRequestFactory.getTxIDFromEnvelopeBytes(data));
                            }
                            transactionException =
                                    new TransactionException(
                                            connectionResponse.getErrorCode(),
                                            connectionResponse.getErrorMessage());
                        } catch (Exception e) {
                            String errorMessage =
                                    "Fabric driver callByProxy onResponse exception: " + e;
                            logger.error(errorMessage);
                            transactionException =
                                    TransactionException.Builder.newInternalException(errorMessage);
                        }
                        callback.onTransactionResponse(transactionException, response);
                    });

        } catch (Exception e) {
            callback.onTransactionResponse(
                    new TransactionException(
                            TransactionException.ErrorCode.INTERNAL_ERROR,
                            "asyncCallByProxy exception: " + e),
                    null);
        }
    }

    @Override
    public void asyncSendTransaction(
            TransactionContext context,
            TransactionRequest request,
            boolean byProxy,
            Connection connection,
            Callback callback) {
        if (byProxy) {
            asyncSendTransactionByProxy(context, request, connection, callback);
        } else {
            asyncSendTransaction(context, request, connection, callback);
        }
    }

    private void asyncSendTransaction(
            TransactionContext transactionContext,
            TransactionRequest transactionRequest,
            Connection connection,
            Driver.Callback callback) {
        try {
            // check
            checkRequest(transactionContext, transactionRequest);

            // Send to endorser
            byte[] data =
                    EndorserRequestFactory.buildProposalRequestBytes(
                            transactionContext, transactionRequest);
            TransactionParams transactionParams =
                    new TransactionParams(transactionRequest, data, false);

            Request endorserRequest = new Request();
            endorserRequest.setData(transactionParams.toBytes());
            endorserRequest.setType(FabricType.ConnectionMessage.FABRIC_SENDTRANSACTION_ENDORSER);
            endorserRequest.setResourceInfo(transactionContext.getResourceInfo());

            connection.asyncSend(
                    endorserRequest,
                    endorserResponse ->
                            asyncSendTransactionHandleEndorserResponse(
                                    transactionContext,
                                    data,
                                    endorserResponse,
                                    connection,
                                    callback));

        } catch (Exception e) {
            String errorMessage = "Fabric driver call exception: " + e;
            logger.error(errorMessage);
            TransactionResponse response = new TransactionResponse();
            TransactionException transactionException =
                    TransactionException.Builder.newInternalException(errorMessage);
            callback.onTransactionResponse(transactionException, response);
        }
    }

    private void asyncSendTransactionByProxy(
            TransactionContext transactionContext,
            TransactionRequest transactionRequest,
            Connection connection,
            Callback callback) {
        try {
            checkProxyRequest(transactionContext, transactionRequest);

            ImmutablePair<TransactionContext, TransactionRequest> proxyRequest =
                    ProxyChaincodeResource.toProxyRequest(
                            transactionContext,
                            transactionRequest,
                            ProxyChaincodeResource.MethodType.SENDTRANSACTION);

            byte[] data =
                    EndorserRequestFactory.buildProposalRequestBytes(
                            proxyRequest.getLeft(), proxyRequest.getRight());
            TransactionParams transactionParams =
                    new TransactionParams(transactionRequest, data, true);

            Request endorserRequest = new Request();
            endorserRequest.setData(transactionParams.toBytes());
            endorserRequest.setType(FabricType.ConnectionMessage.FABRIC_SENDTRANSACTION_ENDORSER);
            endorserRequest.setResourceInfo(transactionContext.getResourceInfo());

            connection.asyncSend(
                    endorserRequest,
                    endorserResponse ->
                            asyncSendTransactionHandleEndorserResponse(
                                    transactionContext,
                                    data,
                                    endorserResponse,
                                    connection,
                                    callback));

        } catch (Exception e) {
            callback.onTransactionResponse(
                    new TransactionException(
                            TransactionException.ErrorCode.INTERNAL_ERROR,
                            "asyncSendTransactionByProxy exception: " + e),
                    null);
        }
    }

    @Override
    public void asyncGetBlockNumber(Connection connection, GetBlockNumberCallback callback) {
        // Test failed
        Request request = new Request();
        request.setType(FabricType.ConnectionMessage.FABRIC_GET_BLOCK_NUMBER);

        connection.asyncSend(
                request,
                response -> {
                    if (response.getErrorCode() == FabricType.TransactionResponseStatus.SUCCESS) {
                        long blockNumber = bytesToLong(response.getData());
                        logger.debug("Get block number: {}", blockNumber);
                        callback.onResponse(null, blockNumber);
                    } else {
                        String errorMsg = "Get block number failed: " + response.getErrorMessage();
                        logger.warn(errorMsg);
                        callback.onResponse(new Exception(errorMsg), -1);
                    }
                });
    }

    @Override
    public void asyncGetBlock(
            long blockNumber,
            boolean onlyHeader,
            Connection connection,
            GetBlockCallback callback) {
        byte[] numberBytes = longToBytes(blockNumber);

        Request request = new Request();
        request.setType(FabricType.ConnectionMessage.FABRIC_GET_BLOCK);
        request.setData(numberBytes);
        String blockVerifierString = connection.getProperties().get(FabricType.FABRIC_VERIFIER);
        connection.asyncSend(
                request,
                response -> {
                    if (response.getErrorCode() == FabricType.TransactionResponseStatus.SUCCESS) {
                        Block block = new Block();
                        block.setRawBytes(response.getData());

                        FabricBlock fabricBlock = null;
                        List<String> transactionsHashes = new ArrayList<>();
                        try {
                            fabricBlock = FabricBlock.encode(response.getData());
                            if (blockVerifierString != null) {
                                if (logger.isDebugEnabled()) {
                                    logger.debug(
                                            "asyncGetBlock: blockVerifierString is not null, enable verify Fabric block, "
                                                    + "blockVerifierString is {}",
                                            blockVerifierString);
                                }
                                if (!fabricBlock.verify(blockVerifierString)) {

                                    logger.error(
                                            "block {} verify failed: {}",
                                            fabricBlock.getHeader().getNumber(),
                                            java.util.Base64.getEncoder()
                                                    .encodeToString(response.getData()));

                                    callback.onResponse(
                                            new Exception(
                                                    "block "
                                                            + fabricBlock.getHeader().getNumber()
                                                            + " verify failed"),
                                            null);
                                    return;
                                }
                            }

                            if (!onlyHeader) {
                                transactionsHashes = new ArrayList<>(fabricBlock.getValidTxs());
                            }
                            block.setBlockHeader(fabricBlock.dumpWeCrossHeader());
                            block.setTransactionsHashes(transactionsHashes);

                            callback.onResponse(null, block);
                        } catch (Exception e) {
                            String errorMsg =
                                    "Invalid fabric block format: " + response.getErrorMessage();
                            logger.warn("e: ", e);
                            callback.onResponse(new Exception(errorMsg), null);
                        }
                    } else {
                        String errorMsg = "Get block failed: " + response.getErrorMessage();
                        logger.warn(errorMsg);
                        callback.onResponse(new Exception(errorMsg), null);
                    }
                });
    }

    @Override
    public void asyncGetTransaction(
            String transactionHash,
            long blockNumber,
            BlockManager blockManager,
            boolean isVerified,
            Connection connection,
            GetTransactionCallback callback) {

        Request request =
                Request.newRequest(
                        FabricType.ConnectionMessage.FABRIC_GET_TRANSACTION,
                        transactionHash.getBytes(StandardCharsets.UTF_8));

        connection.asyncSend(
                request,
                response -> {
                    try {
                        if (response.getErrorCode()
                                == FabricType.TransactionResponseStatus.SUCCESS) {
                            // Generate Verified transaction
                            FabricTransaction fabricTransaction =
                                    FabricTransaction.buildFromEnvelopeBytes(response.getData());

                            String txID = fabricTransaction.getTxID();
                            String payload = new String(response.getData(), "UTF-8");
                            int from = payload.indexOf("-----BEGIN CERTIFICATE-----");
                            int end = payload.indexOf("-----END CERTIFICATE-----");
                            String identity =
                                    payload.substring(from, end) + "-----END CERTIFICATE-----\n";

                            if (!transactionHash.equals(txID)) {
                                throw new Exception(
                                        "Request txHash: "
                                                + transactionHash
                                                + " but response: "
                                                + txID);
                            }
                            Transaction transaction = parseFabricTransaction(fabricTransaction);
                            transaction
                                    .getTransactionResponse()
                                    .setErrorCode(FabricType.TransactionResponseStatus.SUCCESS);
                            transaction.setAccountIdentity(identity);
                            transaction.getTransactionResponse().setHash(txID);
                            transaction.setTxBytes(response.getData());
                            transaction.getTransactionResponse().setBlockNumber(blockNumber);

                            if (isVerified) {
                                asyncVerifyTransactionOnChain(
                                        txID,
                                        blockNumber,
                                        blockManager,
                                        hasOnChain -> {
                                            if (!hasOnChain.booleanValue()) {
                                                callback.onResponse(
                                                        new Exception(
                                                                "Transaction proof verify failed. Tx("
                                                                        + txID
                                                                        + ") is invalid or not on block("
                                                                        + blockNumber
                                                                        + ")"),
                                                        null);
                                            } else {
                                                callback.onResponse(null, transaction);
                                            }
                                        });
                            } else {
                                callback.onResponse(null, transaction);
                            }
                        } else {
                            callback.onResponse(new Exception(response.getErrorMessage()), null);
                        }
                    } catch (Exception e) {
                        callback.onResponse(e, null);
                    }
                });
    }

    private void asyncSendTransactionHandleEndorserResponse(
            TransactionContext transactionContext,
            byte[] envelopeRequestData,
            Response endorserResponse,
            Connection connection,
            Driver.Callback callback) {
        if (endorserResponse.getErrorCode() != FabricType.TransactionResponseStatus.SUCCESS) {
            TransactionResponse response = new TransactionResponse();
            TransactionException transactionException =
                    new TransactionException(
                            endorserResponse.getErrorCode(), endorserResponse.getErrorMessage());
            callback.onTransactionResponse(transactionException, response);
        } else {
            // Send to orderer
            try {
                byte[] ordererPayloadToSign = endorserResponse.getData();
                Request ordererRequest =
                        OrdererRequestFactory.build(
                                transactionContext.getAccount(), ordererPayloadToSign);
                ordererRequest.setType(FabricType.ConnectionMessage.FABRIC_SENDTRANSACTION_ORDERER);
                ordererRequest.setResourceInfo(transactionContext.getResourceInfo());

                connection.asyncSend(
                        ordererRequest,
                        ordererResponse ->
                                asyncSendTransactionHandleOrdererResponse(
                                        transactionContext,
                                        envelopeRequestData,
                                        ordererPayloadToSign,
                                        ordererResponse,
                                        callback));

            } catch (Exception e) {
                String errorMessage = "Fabric driver call orderer exception: " + e;
                logger.error(errorMessage);
                TransactionResponse response = new TransactionResponse();
                TransactionException transactionException =
                        TransactionException.Builder.newInternalException(errorMessage);
                callback.onTransactionResponse(transactionException, response);
            }
        }
    }

    private void asyncSendTransactionHandleOrdererResponse(
            TransactionContext transactionContext,
            byte[] envelopeRequestData,
            byte[] ordererPayloadToSign,
            Response ordererResponse,
            Driver.Callback callback) {
        try {
            if (ordererResponse.getErrorCode() == FabricType.TransactionResponseStatus.SUCCESS) {
                // Success, verify transaction
                String txID = EndorserRequestFactory.getTxIDFromEnvelopeBytes(envelopeRequestData);
                long txBlockNumber = bytesToLong(ordererResponse.getData());

                asyncVerifyTransactionOnChain(
                        txID,
                        txBlockNumber,
                        transactionContext.getBlockManager(),
                        verifyResult -> {
                            TransactionResponse response = new TransactionResponse();
                            TransactionException transactionException = null;
                            try {
                                if (!verifyResult.booleanValue()) {
                                    transactionException =
                                            new TransactionException(
                                                    FabricType.TransactionResponseStatus
                                                            .FABRIC_TX_ONCHAIN_VERIFY_FAIED,
                                                    "Transaction proof verify failed. Tx("
                                                            + txID
                                                            + ") is invalid or not on block("
                                                            + txBlockNumber
                                                            + ")");
                                } else {
                                    response =
                                            decodeTransactionResponse(
                                                    FabricTransaction.buildFromPayloadBytes(
                                                                    ordererPayloadToSign)
                                                            .getOutputBytes());
                                    response.setHash(txID);
                                    response.setBlockNumber(txBlockNumber);
                                    response.setErrorCode(
                                            FabricType.TransactionResponseStatus.SUCCESS);
                                    response.setMessage("Success");
                                    transactionException =
                                            TransactionException.Builder.newSuccessException();
                                }
                            } catch (Exception e) {
                                transactionException =
                                        new TransactionException(
                                                FabricType.TransactionResponseStatus
                                                        .FABRIC_TX_ONCHAIN_VERIFY_FAIED,
                                                "Transaction proof verify failed. Tx("
                                                        + txID
                                                        + ") is invalid or not on block("
                                                        + txBlockNumber
                                                        + ") Internal error: "
                                                        + e);
                            }
                            callback.onTransactionResponse(transactionException, response);
                        });

            } else if (ordererResponse.getErrorCode()
                    == FabricType.TransactionResponseStatus.FABRIC_EXECUTE_CHAINCODE_FAILED) {
                TransactionResponse response = new TransactionResponse();
                Integer errorCode = new Integer(ordererResponse.getData()[0]);
                // If transaction execute failed, fabric TxValidationCode is in data
                TransactionException transactionException =
                        new TransactionException(
                                ordererResponse.getErrorCode(), ordererResponse.getErrorMessage());
                response.setErrorCode(errorCode);
                response.setMessage(ordererResponse.getErrorMessage());
                callback.onTransactionResponse(transactionException, response);
            } else {
                TransactionResponse response = new TransactionResponse();
                TransactionException transactionException =
                        new TransactionException(
                                ordererResponse.getErrorCode(), ordererResponse.getErrorMessage());
                callback.onTransactionResponse(transactionException, response);
            }

        } catch (Exception e) {
            String errorMessage = "Fabric driver call handle orderer response exception: " + e;
            logger.error(errorMessage);
            TransactionResponse response = new TransactionResponse();
            response.setErrorCode(FabricType.TransactionResponseStatus.INTERNAL_ERROR);
            TransactionException transactionException =
                    TransactionException.Builder.newInternalException(errorMessage);
            callback.onTransactionResponse(transactionException, response);
        }
    }

    public void asyncQueryCommittedChaincode(
            TransactionContext transactionContext,
            QueryCommittedRequest queryCommittedRequest,
            Connection connection,
            Driver.Callback callback) {
        try {
            checkQueryCommittedRequest(transactionContext, queryCommittedRequest);

            Request request =
                    EndorserRequestFactory.buildQueryCommittedProposalRequest(
                            transactionContext, queryCommittedRequest);
            request.setType(FabricType.ConnectionMessage.FABRIC_CALL);

            if (transactionContext.getResourceInfo() == null) {
                ResourceInfo resourceInfo = new ResourceInfo();
                request.setResourceInfo(resourceInfo);
            } else {
                request.setResourceInfo(transactionContext.getResourceInfo());
            }

            byte[] envelopeRequestData = TransactionParams.parseFrom(request.getData()).getData();
            connection.asyncSend(
                    request,
                    connectionResponse -> {
                        TransactionResponse response = new TransactionResponse();
                        TransactionException transactionException;
                        try {
                            if (connectionResponse.getErrorCode()
                                    == FabricType.TransactionResponseStatus.SUCCESS) {

                                Lifecycle.QueryChaincodeDefinitionResult chaincodeDefinitionResult =
                                        Lifecycle.QueryChaincodeDefinitionResult.parseFrom(
                                                connectionResponse.getData());
                                Long sequence = chaincodeDefinitionResult.getSequence();

                                String[] result = new String[] {sequence.toString()};

                                response.setResult(result);
                                response.setHash(
                                        EndorserRequestFactory.getTxIDFromEnvelopeBytes(
                                                envelopeRequestData));
                            }
                            transactionException =
                                    new TransactionException(
                                            connectionResponse.getErrorCode(),
                                            connectionResponse.getErrorMessage());
                        } catch (Exception e) {
                            String errorMessage =
                                    "Fabric driver install chaincode onResponse exception: " + e;
                            logger.error(errorMessage);
                            transactionException =
                                    TransactionException.Builder.newInternalException(errorMessage);
                        }
                        callback.onTransactionResponse(transactionException, response);
                    });

        } catch (Exception e) {
            String errorMessage = "Fabric driver install exception: " + e;
            logger.error(errorMessage);
            TransactionResponse response = new TransactionResponse();
            TransactionException transactionException =
                    TransactionException.Builder.newInternalException(errorMessage);
            callback.onTransactionResponse(transactionException, response);
        }
    }

    public void asyncInstallChaincode(
            TransactionContext transactionContext,
            InstallChaincodeRequest installChaincodeRequest,
            Connection connection,
            Driver.Callback callback) {
        try {
            checkInstallRequest(transactionContext, installChaincodeRequest);

            Request installRequest =
                    EndorserRequestFactory.buildInstallProposalRequest(
                            transactionContext, installChaincodeRequest);
            installRequest.setType(
                    FabricType.ConnectionMessage.FABRIC_SENDTRANSACTION_ORG_ENDORSER);

            if (transactionContext.getResourceInfo() == null) {
                ResourceInfo resourceInfo = new ResourceInfo();
                installRequest.setResourceInfo(resourceInfo);
            }

            byte[] envelopeRequestData =
                    TransactionParams.parseFrom(installRequest.getData()).getData();
            connection.asyncSend(
                    installRequest,
                    connectionResponse -> {
                        TransactionResponse response = new TransactionResponse();
                        TransactionException transactionException;
                        try {
                            if (connectionResponse.getErrorCode()
                                    == FabricType.TransactionResponseStatus.SUCCESS) {
                                FabricTransaction fabricTransaction =
                                        FabricTransaction.buildFromPayloadBytes(
                                                connectionResponse.getData());

                                Lifecycle.InstallChaincodeResult installChaincodeResult =
                                        Lifecycle.InstallChaincodeResult.parseFrom(
                                                fabricTransaction.getOutputBytes());
                                String packageId = installChaincodeResult.getPackageId();

                                String[] result = new String[] {packageId};

                                response.setResult(result);
                                response.setHash(
                                        EndorserRequestFactory.getTxIDFromEnvelopeBytes(
                                                envelopeRequestData));
                            }
                            transactionException =
                                    new TransactionException(
                                            connectionResponse.getErrorCode(),
                                            connectionResponse.getErrorMessage());
                        } catch (Exception e) {
                            String errorMessage =
                                    "Fabric driver install chaincode onResponse exception: " + e;
                            logger.error(errorMessage);
                            transactionException =
                                    TransactionException.Builder.newInternalException(errorMessage);
                        }
                        callback.onTransactionResponse(transactionException, response);
                    });

        } catch (Exception e) {
            String errorMessage = "Fabric driver install exception: " + e;
            logger.error(errorMessage);
            TransactionResponse response = new TransactionResponse();
            TransactionException transactionException =
                    TransactionException.Builder.newInternalException(errorMessage);
            callback.onTransactionResponse(transactionException, response);
        }
    }

    public void asyncApproveChaincode(
            TransactionContext transactionContext,
            ApproveChaincodeRequest approveChaincodeRequest,
            Connection connection,
            Driver.Callback callback) {
        try {
            checkApproveRequest(transactionContext, approveChaincodeRequest);

            Request request =
                    EndorserRequestFactory.buildApproveProposalRequest(
                            transactionContext, approveChaincodeRequest);
            request.setType(FabricType.ConnectionMessage.FABRIC_SENDTRANSACTION_ORG_ENDORSER);

            if (transactionContext.getResourceInfo() == null) {
                ResourceInfo resourceInfo = new ResourceInfo();
                request.setResourceInfo(resourceInfo);
            }

            byte[] envelopeRequestData = TransactionParams.parseFrom(request.getData()).getData();
            connection.asyncSend(
                    request,
                    endorserResponse -> {
                        asyncSendTransactionHandleEndorserResponse(
                                transactionContext,
                                envelopeRequestData,
                                endorserResponse,
                                connection,
                                callback);
                    });

        } catch (Exception e) {
            String errorMessage = "Fabric driver install exception: " + e;
            logger.error(errorMessage);
            TransactionResponse response = new TransactionResponse();
            TransactionException transactionException =
                    TransactionException.Builder.newInternalException(errorMessage);
            callback.onTransactionResponse(transactionException, response);
        }
    }

    public void asyncCommitChaincode(
            TransactionContext transactionContext,
            CommitChaincodeRequest commitChaincodeRequest,
            Connection connection,
            Driver.Callback callback) {
        try {
            checkCommitRequest(transactionContext, commitChaincodeRequest);

            Request request =
                    EndorserRequestFactory.buildCommitProposalRequest(
                            transactionContext, commitChaincodeRequest);
            request.setType(FabricType.ConnectionMessage.FABRIC_SENDTRANSACTION_ORG_ENDORSER);

            if (transactionContext.getResourceInfo() == null) {
                ResourceInfo resourceInfo = new ResourceInfo();
                request.setResourceInfo(resourceInfo);
            }

            byte[] envelopeRequestData = TransactionParams.parseFrom(request.getData()).getData();
            connection.asyncSend(
                    request,
                    endorserResponse -> {
                        asyncSendTransactionHandleEndorserResponse(
                                transactionContext,
                                envelopeRequestData,
                                endorserResponse,
                                connection,
                                callback);
                    });

        } catch (Exception e) {
            String errorMessage = "Fabric driver install exception: " + e;
            logger.error(errorMessage);
            TransactionResponse response = new TransactionResponse();
            TransactionException transactionException =
                    TransactionException.Builder.newInternalException(errorMessage);
            callback.onTransactionResponse(transactionException, response);
        }
    }

    public void asyncInitChaincode(
            TransactionContext transactionContext,
            TransactionRequest transactionRequest,
            Connection connection,
            Driver.Callback callback) {
        try {
            // check
            checkRequest(transactionContext, transactionRequest);

            // Send to endorser
            byte[] data =
                    EndorserRequestFactory.buildProposalRequestBytes(
                            transactionContext, transactionRequest, true);
            TransactionParams transactionParams =
                    new TransactionParams(transactionRequest, data, false);

            Request endorserRequest = new Request();
            endorserRequest.setData(transactionParams.toBytes());
            endorserRequest.setType(FabricType.ConnectionMessage.FABRIC_SENDTRANSACTION_ENDORSER);
            endorserRequest.setResourceInfo(transactionContext.getResourceInfo());

            connection.asyncSend(
                    endorserRequest,
                    endorserResponse ->
                            asyncSendTransactionHandleEndorserResponse(
                                    transactionContext,
                                    data,
                                    endorserResponse,
                                    connection,
                                    callback));

        } catch (Exception e) {
            String errorMessage = "Fabric driver call exception: " + e;
            logger.error(errorMessage);
            TransactionResponse response = new TransactionResponse();
            TransactionException transactionException =
                    TransactionException.Builder.newInternalException(errorMessage);
            callback.onTransactionResponse(transactionException, response);
        }
    }

    public void asyncInstantiateChaincode(
            TransactionContext transactionContext,
            InstantiateChaincodeRequest instantiateChaincodeRequest,
            Connection connection,
            Driver.Callback callback) {
        try {
            checkInstantiateRequest(transactionContext, instantiateChaincodeRequest);

            Request instantiateRequest =
                    EndorserRequestFactory.buildInstantiateProposalRequest(
                            transactionContext, instantiateChaincodeRequest);
            instantiateRequest.setType(
                    FabricType.ConnectionMessage.FABRIC_SENDTRANSACTION_ORG_ENDORSER);

            if (transactionContext.getResourceInfo() == null) {
                ResourceInfo resourceInfo = new ResourceInfo();
                instantiateRequest.setResourceInfo(resourceInfo);
            }

            byte[] envelopeRequestData =
                    TransactionParams.parseFrom(instantiateRequest.getData()).getData();
            connection.asyncSend(
                    instantiateRequest,
                    endorserResponse ->
                            asyncSendTransactionHandleEndorserResponse(
                                    transactionContext,
                                    envelopeRequestData,
                                    endorserResponse,
                                    connection,
                                    callback));

        } catch (Exception e) {
            String errorMessage = "Fabric driver instantiate exception: " + e;
            logger.error(errorMessage);
            TransactionResponse response = new TransactionResponse();
            TransactionException transactionException =
                    TransactionException.Builder.newInternalException(errorMessage);
            callback.onTransactionResponse(transactionException, response);
        }
    }

    @Override
    public void asyncCustomCommand(
            String command,
            Path path,
            Object[] args,
            Account account,
            BlockManager blockManager,
            Connection connection,
            CustomCommandCallback callback) {
        switch (command) {
                //            package
                //            install
                //            appover
                //            commit
                //            init
            case InstallCommand.NAME:
                handleInstallCommand(args, account, blockManager, connection, callback);
                break;
            case InstantiateCommand.NAME:
                handleInstantiateCommand(args, account, blockManager, connection, callback);
                break;
            case UpgradeCommand.NAME:
                handleUpgradeCommand(args, account, blockManager, connection, callback);
                break;
            default:
                callback.onResponse(new Exception("Unsupported command for Fabric plugin"), null);
                break;
        }
    }

    @Override
    public byte[] accountSign(Account account, byte[] message) {
        if (!(account instanceof FabricAccount)) {
            throw new UnsupportedOperationException(
                    "Not FabricAccount, account name: " + account.getClass().getName());
        }

        try {
            byte[] signBytes = ((FabricAccount) account).sign(message);
            logger.debug(
                    "accountSign: {}, message: {}, signBytes: {}",
                    account.getName(),
                    message.toString(),
                    Arrays.toString(signBytes));
            return signBytes;
        } catch (Exception e) {
            logger.error("accountSign exception: ", e);
            return null;
        }
    }

    @Override
    public boolean accountVerify(String identity, byte[] signBytes, byte[] message) {
        FabricAccount fabricAccount =
                FabricAccountFactory.build("temp-to-verify-" + identity, "", identity, null);
        try {
            logger.debug(
                    "accountVerify: {}, signBytes:{}, message: {} ",
                    identity,
                    Arrays.toString(signBytes),
                    message);
            return fabricAccount.verifySign(message, signBytes);
        } catch (Exception e) {
            logger.error("accountVerify exception: ", e);
            return false;
        }
    }

    private void handleInstallCommand(
            Object[] args,
            Account account,
            BlockManager blockManager,
            Connection connection,
            CustomCommandCallback callback) {

        try {
            FabricConnection.Properties properties =
                    FabricConnection.Properties.parseFromMap(connection.getProperties());
            String channelName = properties.getChannelName();
            if (channelName == null) {
                throw new Exception("Connection properties(ChannelName) is not set");
            }

            InstallChaincodeRequest installChaincodeRequest =
                    InstallCommand.parseEncodedArgs(args, channelName); // parse args from sdk

            TransactionContext transactionContext =
                    new TransactionContext(account, null, null, blockManager);

            asyncInstallChaincode(
                    transactionContext,
                    installChaincodeRequest,
                    connection,
                    (transactionException, transactionResponse) -> {
                        if (transactionException.isSuccess()) {
                            callback.onResponse(null, "Success");
                        } else {
                            callback.onResponse(
                                    transactionException,
                                    "Failed: " + transactionException.getMessage());
                        }
                    });

        } catch (Exception e) {
            callback.onResponse(e, "Failed: " + e.getMessage());
        }
    }

    private void handleInstantiateCommand(
            Object[] args,
            Account account,
            BlockManager blockManager,
            Connection connection,
            CustomCommandCallback callback) {
        try {
            FabricConnection.Properties properties =
                    FabricConnection.Properties.parseFromMap(connection.getProperties());
            String channelName = properties.getChannelName();
            if (channelName == null) {
                throw new Exception("Connection properties(ChannelName) is not set");
            }

            InstantiateChaincodeRequest instantiateChaincodeRequest =
                    InstantiateCommand.parseEncodedArgs(args, channelName);

            TransactionContext transactionContext =
                    new TransactionContext(account, null, null, blockManager);

            AtomicBoolean hasResponsed = new AtomicBoolean(false);
            asyncInstantiateChaincode(
                    transactionContext,
                    instantiateChaincodeRequest,
                    connection,
                    (transactionException, transactionResponse) -> {
                        logger.debug(
                                "asyncInstantiateChaincode response:{} e:",
                                transactionResponse,
                                transactionException);
                        if (!hasResponsed.getAndSet(true)) {
                            if (transactionException.isSuccess()) {
                                callback.onResponse(null, new String("Success"));
                            } else {
                                callback.onResponse(
                                        transactionException,
                                        new String("Failed: ") + transactionException.getMessage());
                            }
                        }
                    });
            Thread.sleep(5000); // Sleep for error response
            if (!hasResponsed.getAndSet(true)) {
                callback.onResponse(
                        null,
                        "Instantiating... Please wait and use 'listResources' to check. See router's log for more information.");
            }

        } catch (Exception e) {
            callback.onResponse(e, "Failed: " + e.getMessage());
        }
    }

    private void handleUpgradeCommand(
            Object[] args,
            Account account,
            BlockManager blockManager,
            Connection connection,
            CustomCommandCallback callback) {
        try {
            FabricConnection.Properties properties =
                    FabricConnection.Properties.parseFromMap(connection.getProperties());
            String channelName = properties.getChannelName();
            if (channelName == null) {
                throw new Exception("Connection properties(ChannelName) is not set");
            }

            UpgradeChaincodeRequest upgradeChaincodeRequest =
                    UpgradeCommand.parseEncodedArgs(args, channelName);

            TransactionContext transactionContext =
                    new TransactionContext(account, null, null, blockManager);

            AtomicBoolean hasResponsed = new AtomicBoolean(false);
            asyncUpgradeChaincode(
                    transactionContext,
                    upgradeChaincodeRequest,
                    connection,
                    (transactionException, transactionResponse) -> {
                        logger.debug(
                                "asyncUpgradeChaincode response:{} e:",
                                transactionResponse,
                                transactionException);
                        if (!hasResponsed.getAndSet(true)) {
                            if (transactionException.isSuccess()) {
                                callback.onResponse(null, new String("Success"));
                            } else {
                                callback.onResponse(
                                        transactionException,
                                        new String("Failed: ") + transactionException.getMessage());
                            }
                        }
                    });
            Thread.sleep(5000); // Sleep for error response
            if (!hasResponsed.getAndSet(true)) {
                callback.onResponse(
                        null,
                        "Upgrading... Please wait and use 'detail' to check the version. See router's log for more information.");
            }

        } catch (Exception e) {
            callback.onResponse(e, "Failed: " + e.getMessage());
        }
    }

    public void asyncUpgradeChaincode(
            TransactionContext transactionContext,
            UpgradeChaincodeRequest upgradeChaincodeRequest,
            Connection connection,
            Driver.Callback callback) {
        try {
            checkUpgradeRequest(transactionContext, upgradeChaincodeRequest);

            Request upgradeRequest =
                    EndorserRequestFactory.buildUpgradeProposalRequest(
                            transactionContext, upgradeChaincodeRequest);
            upgradeRequest.setType(
                    FabricType.ConnectionMessage.FABRIC_SENDTRANSACTION_ORG_ENDORSER);

            if (transactionContext.getResourceInfo() == null) {
                ResourceInfo resourceInfo = new ResourceInfo();
                upgradeRequest.setResourceInfo(resourceInfo);
            }

            byte[] envelopeRequestData =
                    TransactionParams.parseFrom(upgradeRequest.getData()).getData();
            connection.asyncSend(
                    upgradeRequest,
                    endorserResponse ->
                            asyncSendTransactionHandleEndorserResponse(
                                    transactionContext,
                                    envelopeRequestData,
                                    endorserResponse,
                                    connection,
                                    callback));

        } catch (Exception e) {
            String errorMessage = "Fabric driver upgrade exception: " + e;
            logger.error(errorMessage);
            TransactionResponse response = new TransactionResponse();
            TransactionException transactionException =
                    TransactionException.Builder.newInternalException(errorMessage);
            callback.onTransactionResponse(transactionException, response);
        }
    }

    private void asyncVerifyTransactionOnChain(
            String txID,
            long blockNumber,
            BlockManager blockHeaderManager,
            Consumer<Boolean> callback) {
        logger.debug("To verify transaction, waiting fabric block syncing ...");
        blockHeaderManager.asyncGetBlock(
                blockNumber,
                (e, block) -> {
                    logger.debug("Receive block, verify transaction ...");
                    boolean verifyResult = false;
                    try {
                        FabricBlock fabricBlock = FabricBlock.encode(block.getRawBytes());
                        verifyResult = fabricBlock.hasTransaction(txID);
                        logger.debug(
                                "Tx(block: "
                                        + blockNumber
                                        + "): "
                                        + txID
                                        + " verify: "
                                        + verifyResult);
                    } catch (Exception e1) {
                        logger.debug("Consumer accept exception: ", e1);
                        verifyResult = false;
                    }
                    callback.accept(verifyResult);
                });
    }

    private void checkRequest(
            TransactionContext transactionContext, TransactionRequest transactionRequest)
            throws Exception {
        if (transactionContext.getAccount() == null) {
            throw new Exception("Unknown account");
        }

        if (!transactionContext.getAccount().getType().equals(FabricType.Account.FABRIC_ACCOUNT)) {
            throw new Exception(
                    "Illegal account type for fabric call: "
                            + transactionContext.getAccount().getType());
        }

        if (transactionContext.getBlockManager() == null) {
            throw new Exception("blockManager is null");
        }

        if (transactionContext.getResourceInfo() == null) {
            throw new Exception("resourceInfo is null");
        }

        if (transactionRequest == null) {
            throw new Exception("TransactionRequest is null");
        }

        if (transactionRequest.getArgs() == null) {
            // Fabric has no null args, just pass it as String[0]
            transactionRequest.setArgs(new String[0]);
        }
    }

    private void checkProxyRequest(
            TransactionContext transactionContext, TransactionRequest transactionRequest)
            throws Exception {
        if (transactionContext.getResourceInfo() == null) {
            throw new Exception("resourceInfo is null");
        }

        String isTemporary =
                (String) transactionContext.getResourceInfo().getProperties().get("isTemporary");
        if (isTemporary != null && isTemporary.equals("true")) {
            throw new Exception(
                    "Fabric resource "
                            + transactionContext.getResourceInfo().getName()
                            + " not found");
        }

        if (transactionContext.getAccount() == null) {
            throw new Exception("Unknown account: " + transactionContext.getAccount());
        }

        if (!transactionContext.getAccount().getType().equals(FabricType.Account.FABRIC_ACCOUNT)) {
            throw new Exception(
                    "Illegal account type for fabric call: "
                            + transactionContext.getAccount().getType());
        }
    }

    private void checkQueryCommittedRequest(
            TransactionContext transactionContext, QueryCommittedRequest request) throws Exception {
        if (request == null) {
            throw new Exception("Request data is null");
        }
        request.check();

        if (transactionContext.getAccount() == null) {
            throw new Exception("Unknown account: " + transactionContext.getAccount());
        }

        if (!transactionContext.getAccount().getType().equals(FabricType.Account.FABRIC_ACCOUNT)) {
            throw new Exception(
                    "Illegal account type for fabric call: "
                            + transactionContext.getAccount().getType());
        }
    }

    private void checkInstallRequest(
            TransactionContext transactionContext, InstallChaincodeRequest request)
            throws Exception {
        if (request == null) {
            throw new Exception("Request data is null");
        }
        request.check();

        if (transactionContext.getAccount() == null) {
            throw new Exception("Unknown account: " + transactionContext.getAccount());
        }

        if (!transactionContext.getAccount().getType().equals(FabricType.Account.FABRIC_ACCOUNT)) {
            throw new Exception(
                    "Illegal account type for fabric call: "
                            + transactionContext.getAccount().getType());
        }
    }

    private void checkApproveRequest(
            TransactionContext transactionContext, ApproveChaincodeRequest request)
            throws Exception {
        if (request == null) {
            throw new Exception("Request data is null");
        }
        request.check();

        if (transactionContext.getAccount() == null) {
            throw new Exception("Unknown account: " + transactionContext.getAccount());
        }

        if (!transactionContext.getAccount().getType().equals(FabricType.Account.FABRIC_ACCOUNT)) {
            throw new Exception(
                    "Illegal account type for fabric call: "
                            + transactionContext.getAccount().getType());
        }
    }

    private void checkCommitRequest(
            TransactionContext transactionContext, CommitChaincodeRequest request)
            throws Exception {
        if (request == null) {
            throw new Exception("Request data is null");
        }
        request.check();

        if (transactionContext.getAccount() == null) {
            throw new Exception("Unknown account: " + transactionContext.getAccount());
        }

        if (!transactionContext.getAccount().getType().equals(FabricType.Account.FABRIC_ACCOUNT)) {
            throw new Exception(
                    "Illegal account type for fabric call: "
                            + transactionContext.getAccount().getType());
        }
    }

    private void checkInstantiateRequest(
            TransactionContext transactionContext,
            InstantiateChaincodeRequest instantiateChaincodeRequest)
            throws Exception {
        if (instantiateChaincodeRequest == null) {
            throw new Exception("Request data is null");
        }
        instantiateChaincodeRequest.check();

        if (transactionContext.getAccount() == null) {
            throw new Exception("Unkown account: " + transactionContext.getAccount());
        }

        if (!transactionContext.getAccount().getType().equals(FabricType.Account.FABRIC_ACCOUNT)) {
            throw new Exception(
                    "Illegal account type for fabric call: "
                            + transactionContext.getAccount().getType());
        }
    }

    private void checkUpgradeRequest(
            TransactionContext transactionContext, UpgradeChaincodeRequest upgradeChaincodeRequest)
            throws Exception {
        if (upgradeChaincodeRequest == null) {
            throw new Exception("Request data is null");
        }
        upgradeChaincodeRequest.check();

        if (transactionContext.getAccount() == null) {
            throw new Exception("Unkown account: " + transactionContext.getAccount());
        }

        if (!transactionContext.getAccount().getType().equals(FabricType.Account.FABRIC_ACCOUNT)) {
            throw new Exception(
                    "Illegal account type for fabric call: "
                            + transactionContext.getAccount().getType());
        }
    }

    private Transaction parseFabricTransaction(FabricTransaction fabricTransaction)
            throws Exception {
        String chaincodeName = fabricTransaction.getChaincodeName();
        String[] originArgs = fabricTransaction.getArgs().toArray(new String[] {});
        String method = fabricTransaction.getMethod();

        if (logger.isDebugEnabled()) {
            logger.debug(
                    "chaincodeName: {}, method: {}, originArgs: {}",
                    chaincodeName,
                    fabricTransaction.getMethod(),
                    Arrays.toString(originArgs));
        }

        String[] args = null;
        String resource = chaincodeName;
        String transactionID = "0";
        long seq = 0;

        boolean byProxy = false;
        if (chaincodeName.equals(StubConstant.PROXY_NAME)) {
            byProxy = true;
            if (method.equals("sendTransaction")) {
                args = ProxyChaincodeResource.decodeSendTransactionArgs(originArgs);
                transactionID = originArgs[1];
                seq = Long.valueOf(originArgs[2]);
                // decode path
                resource = originArgs[3].split("\\.")[2];
                method = originArgs[4];
            }
        }

        if (!byProxy) {
            args = originArgs;
            method = fabricTransaction.getMethod();
        }

        /** request */
        Transaction transaction = new Transaction();
        transaction.getTransactionRequest().setArgs(args);
        transaction.getTransactionRequest().setMethod(method);

        /** response */
        byte[] outputBytes = fabricTransaction.getOutputBytes();
        // Fabric only has 1 return object
        ByteString payload = ByteString.copyFrom(outputBytes);
        String[] output = new String[] {payload.toStringUtf8()};
        transaction.getTransactionResponse().setResult(output);

        /** xa */
        transaction.setTransactionByProxy(byProxy);
        transaction
                .getTransactionRequest()
                .getOptions()
                .put(StubConstant.XA_TRANSACTION_ID, transactionID);
        transaction.getTransactionRequest().getOptions().put(StubConstant.XA_TRANSACTION_SEQ, seq);
        transaction.setResource(resource);

        return transaction;
    }
}
