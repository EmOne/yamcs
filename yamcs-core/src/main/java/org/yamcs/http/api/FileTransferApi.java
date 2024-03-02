package org.yamcs.http.api;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yamcs.ServiceWithConfig;
import org.yamcs.YamcsServer;
import org.yamcs.YamcsServerInstance;
import org.yamcs.api.Observer;
import org.yamcs.cfdp.CfdpFileTransfer;
import org.yamcs.cfdp.CfdpTransactionId;
import org.yamcs.filetransfer.FileTransfer;
import org.yamcs.filetransfer.FileTransferService;
import org.yamcs.filetransfer.InvalidRequestException;
import org.yamcs.filetransfer.RemoteFileListMonitor;
import org.yamcs.filetransfer.TransferMonitor;
import org.yamcs.filetransfer.TransferOptions;
import org.yamcs.http.BadRequestException;
import org.yamcs.http.Context;
import org.yamcs.http.InternalServerErrorException;
import org.yamcs.http.NotFoundException;
import org.yamcs.protobuf.AbstractFileTransferApi;
import org.yamcs.protobuf.CancelTransferRequest;
import org.yamcs.protobuf.CreateTransferRequest;
import org.yamcs.protobuf.EntityInfo;
import org.yamcs.protobuf.FileTransferServiceInfo;
import org.yamcs.protobuf.GetFileTransferServiceRequest;
import org.yamcs.protobuf.GetTransferRequest;
import org.yamcs.protobuf.ListFileTransferServicesRequest;
import org.yamcs.protobuf.ListFileTransferServicesResponse;
import org.yamcs.protobuf.ListFilesRequest;
import org.yamcs.protobuf.ListFilesResponse;
import org.yamcs.protobuf.ListTransfersRequest;
import org.yamcs.protobuf.ListTransfersResponse;
import org.yamcs.protobuf.PauseTransferRequest;
import org.yamcs.protobuf.ResumeTransferRequest;
import org.yamcs.protobuf.SubscribeTransfersRequest;
import org.yamcs.protobuf.TransactionId;
import org.yamcs.protobuf.TransferDirection;
import org.yamcs.protobuf.TransferInfo;
import org.yamcs.security.SystemPrivilege;
import org.yamcs.utils.TimeEncoding;
import org.yamcs.yarch.Bucket;
import org.yamcs.yarch.YarchDatabase;
import org.yamcs.yarch.YarchDatabaseInstance;

import com.google.protobuf.Empty;

public class FileTransferApi extends AbstractFileTransferApi<Context> {

    private static final Logger log = LoggerFactory.getLogger(FileTransferApi.class);

    @Override
    public void listFileTransferServices(Context ctx, ListFileTransferServicesRequest request,
            Observer<ListFileTransferServicesResponse> observer) {
        ctx.checkSystemPrivilege(SystemPrivilege.ReadFileTransfers);
        String instance = InstancesApi.verifyInstance(request.getInstance());
        YamcsServer yamcs = YamcsServer.getServer();
        ListFileTransferServicesResponse.Builder responseb = ListFileTransferServicesResponse.newBuilder();
        YamcsServerInstance ysi = yamcs.getInstance(instance);
        for (ServiceWithConfig service : ysi.getServicesWithConfig(FileTransferService.class)) {
            if (service.getService().isRunning()) {
                responseb.addServices(
                        toFileTransferServiceInfo(service.getName(), (FileTransferService) service.getService()));
            }
        }
        observer.complete(responseb.build());
    }

    @Override
    public void getFileTransferService(Context ctx, GetFileTransferServiceRequest request,
            Observer<FileTransferServiceInfo> observer) {
        ctx.checkSystemPrivilege(SystemPrivilege.ReadFileTransfers);
        var ftService = verifyService(request.getInstance(), request.getServiceName());
        observer.complete(toFileTransferServiceInfo(request.getServiceName(), ftService));
    }

    @Override
    public void listTransfers(Context ctx, ListTransfersRequest request, Observer<ListTransfersResponse> observer) {
        ctx.checkSystemPrivilege(SystemPrivilege.ReadFileTransfers);

        FileTransferService ftService = verifyService(request.getInstance(),
                request.hasServiceName() ? request.getServiceName() : null);

        List<FileTransfer> transfers = new ArrayList<>(ftService.getTransfers());
        Collections.sort(transfers, (a, b) -> Long.compare(b.getCreationTime(), a.getCreationTime()));

        ListTransfersResponse.Builder responseb = ListTransfersResponse.newBuilder();
        for (FileTransfer transfer : transfers) {
            responseb.addTransfers(toTransferInfo(ftService, transfer));
        }
        observer.complete(responseb.build());
    }

    @Override
    public void getTransfer(Context ctx, GetTransferRequest request, Observer<TransferInfo> observer) {
        ctx.checkSystemPrivilege(SystemPrivilege.ReadFileTransfers);
        FileTransferService ftService = verifyService(request.getInstance(),
                request.hasServiceName() ? request.getServiceName() : null);
        FileTransfer transaction = verifyTransaction(ftService, request.getId());
        observer.complete(toTransferInfo(ftService, transaction));
    }

    @Override
    public void createTransfer(Context ctx, CreateTransferRequest request, Observer<TransferInfo> observer) {
        ctx.checkSystemPrivilege(SystemPrivilege.ControlFileTransfers);
        FileTransferService ftService = verifyService(request.getInstance(),
                request.hasServiceName() ? request.getServiceName() : null);

        if (!request.hasDirection()) {
            throw new BadRequestException("Direction not specified");
        }

        String bucketName = request.getBucket();
        BucketsApi.checkReadBucketPrivilege(bucketName, ctx.user);

        String objectName = request.getObjectName();

        YarchDatabaseInstance yarch = YarchDatabase.getInstance(YamcsServer.GLOBAL_INSTANCE);

        Bucket bucket;
        try {
            bucket = yarch.getBucket(bucketName);
        } catch (IOException e) {
            throw new InternalServerErrorException("Error while resolving bucket", e);
        }
        if (bucket == null) {
            throw new BadRequestException("No bucket by name '" + bucketName + "'");
        }

        if (request.getDirection() == TransferDirection.UPLOAD) {
            TransferOptions transferOptions = new TransferOptions();
            transferOptions.setOverwrite(true);
            transferOptions.setCreatePath(true);
            transferOptions.putExtraOptions(GpbWellKnownHelper.toJava(request.getOptions()));

            if (transferOptions.isReliable() && transferOptions.isClosureRequested()) {
                throw new BadRequestException("Cannot set both reliable and closureRequested options");
            }
            String destinationPath = request.hasRemotePath() ? request.getRemotePath() : null;
            String source = request.hasSource() ? request.getSource() : null;
            String destination = request.hasDestination() ? request.getDestination() : null;

            try {
                FileTransfer transfer = ftService.startUpload(source, bucket, objectName, destination,
                        destinationPath, transferOptions);
                observer.complete(toTransferInfo(ftService, transfer));

            } catch (InvalidRequestException e) {
                throw new BadRequestException(e.getMessage());
            } catch (IOException e) {
                log.error("Error when retrieving object {} from bucket {}", objectName, bucketName, e);
                throw new InternalServerErrorException("Error when retrieving object: " + e.getMessage());
            }
        } else if (request.getDirection() == TransferDirection.DOWNLOAD) {
            TransferOptions transferOptions = new TransferOptions();
            transferOptions.setOverwrite(true);
            transferOptions.setCreatePath(true);
            transferOptions.putExtraOptions(GpbWellKnownHelper.toJava(request.getOptions()));

            String sourcePath = request.getRemotePath();
            String source = request.hasSource() ? request.getSource() : null;
            String destination = request.hasDestination() ? request.getDestination() : null;

            try {
                FileTransfer transfer = ftService.startDownload(source, sourcePath, destination, bucket, objectName,
                        transferOptions);
                observer.complete(toTransferInfo(ftService, transfer));
            } catch (InvalidRequestException e) {
                throw new BadRequestException(e.getMessage());
            } catch (IOException e) {
                log.error("Error when retrieving object {} from bucket {}", objectName, bucketName, e);
                throw new InternalServerErrorException("Error when retrieving object: " + e.getMessage());
            }
        } else {
            throw new BadRequestException("Unexpected direction '" + request.getDirection() + "'");
        }
    }

    @Override
    public void pauseTransfer(Context ctx, PauseTransferRequest request, Observer<Empty> observer) {
        ctx.checkSystemPrivilege(SystemPrivilege.ControlFileTransfers);
        FileTransferService ftService = verifyService(request.getInstance(),
                request.hasServiceName() ? request.getServiceName() : null);

        FileTransfer transaction = verifyTransaction(ftService, request.getId());
        if (transaction.pausable()) {
            ftService.pause(transaction);
        } else {
            throw new BadRequestException("Transaction '" + transaction.getId() + "' cannot be paused");
        }
        observer.complete(Empty.getDefaultInstance());
    }

    @Override
    public void cancelTransfer(Context ctx, CancelTransferRequest request, Observer<Empty> observer) {
        ctx.checkSystemPrivilege(SystemPrivilege.ControlFileTransfers);
        FileTransferService ftService = verifyService(request.getInstance(),
                request.hasServiceName() ? request.getServiceName() : null);

        FileTransfer transaction = verifyTransaction(ftService, request.getId());
        if (transaction.cancellable()) {
            ftService.cancel(transaction);
        } else {
            throw new BadRequestException("Transaction '" + transaction.getId() + "' cannot be cancelled");
        }
        observer.complete(Empty.getDefaultInstance());
    }

    @Override
    public void resumeTransfer(Context ctx, ResumeTransferRequest request, Observer<Empty> observer) {
        ctx.checkSystemPrivilege(SystemPrivilege.ControlFileTransfers);
        FileTransferService ftService = verifyService(request.getInstance(),
                request.hasServiceName() ? request.getServiceName() : null);
        FileTransfer transaction = verifyTransaction(ftService, request.getId());

        if (transaction.pausable()) {
            ftService.resume(transaction);
        } else {
            throw new BadRequestException("Transaction '" + transaction.getId() + "' cannot be resumed");
        }
        observer.complete(Empty.getDefaultInstance());
    }

    @Override
    public void subscribeTransfers(Context ctx, SubscribeTransfersRequest request, Observer<TransferInfo> observer) {
        ctx.checkSystemPrivilege(SystemPrivilege.ReadFileTransfers);
        FileTransferService ftService = verifyService(request.getInstance(),
                request.hasServiceName() ? request.getServiceName() : null);
        TransferMonitor listener = transfer -> {
            observer.next(toTransferInfo(ftService, transfer));
        };
        observer.setCancelHandler(() -> ftService.unregisterTransferMonitor(listener));

        for (FileTransfer transfer : ftService.getTransfers()) {
            observer.next(toTransferInfo(ftService, transfer));
        }
        ftService.registerTransferMonitor(listener);
    }

    @Override
    public void subscribeRemoteFileList(Context ctx, SubscribeTransfersRequest request,
            Observer<ListFilesResponse> observer) {
        ctx.checkSystemPrivilege(SystemPrivilege.ReadFileTransfers);
        FileTransferService ftService = verifyService(request.getInstance(),
                request.hasServiceName() ? request.getServiceName() : null);
        RemoteFileListMonitor listener = fileList -> {
            observer.next(fileList);
        };
        observer.setCancelHandler(() -> ftService.unregisterRemoteFileListMonitor(listener));
        ftService.registerRemoteFileListMonitor(listener);
    }

    /**
     * Request file list from remote
     */
    @Override
    public void fetchFileList(Context ctx, ListFilesRequest request, Observer<Empty> observer) {
        ctx.checkSystemPrivilege(SystemPrivilege.ControlFileTransfers);
        FileTransferService ftService = verifyService(request.getInstance(),
                request.hasServiceName() ? request.getServiceName() : null);
        ftService.fetchFileList(request.getSource(), request.getDestination(), request.getRemotePath(),
                GpbWellKnownHelper.toJava(request.getOptions()));
        observer.complete(Empty.getDefaultInstance());
    }

    /**
     * Get latest file list from service
     */
    @Override
    public void getFileList(Context ctx, ListFilesRequest request, Observer<ListFilesResponse> observer) {
        ctx.checkSystemPrivilege(SystemPrivilege.ReadFileTransfers);
        FileTransferService ftService = verifyService(request.getInstance(),
                request.hasServiceName() ? request.getServiceName() : null);
        ListFilesResponse response = ftService.getFileList(request.getSource(), request.getDestination(),
                request.getRemotePath(), GpbWellKnownHelper.toJava(request.getOptions()));
        if (response == null) {
            response = ListFilesResponse.newBuilder().build();
        }
        observer.complete(response);
    }

    private static FileTransferServiceInfo toFileTransferServiceInfo(String name, FileTransferService service) {
        FileTransferServiceInfo.Builder infob = FileTransferServiceInfo.newBuilder()
                .setInstance(service.getYamcsInstance())
                .setName(name);
        infob.addAllLocalEntities(service.getLocalEntities());
        infob.addAllRemoteEntities(service.getRemoteEntities());
        infob.setCapabilities(service.getCapabilities());
        infob.addAllTransferOptions(service.getFileTransferOptions());
        return infob.build();
    }

    private FileTransfer verifyTransaction(FileTransferService ftService, long id) throws NotFoundException {
        FileTransfer transaction = ftService.getFileTransfer(id);
        if (transaction == null) {
            throw new NotFoundException("No such transaction");
        } else {
            return transaction;
        }
    }

    private static TransferInfo toTransferInfo(FileTransferService service, FileTransfer transfer) {
        TransferInfo.Builder tib = TransferInfo.newBuilder()
                .setId(transfer.getId())
                .setState(transfer.getTransferState())
                .setDirection(transfer.getDirection())
                .setSizeTransferred(transfer.getTransferredSize())
                .setReliable(transfer.isReliable());

        if (transfer.getTotalSize() >= 0) {
            tib.setTotalSize(transfer.getTotalSize());
        }
        if (transfer.getBucketName() != null) {
            tib.setBucket(transfer.getBucketName());
        }
        if (transfer.getObjectName() != null) {
            tib.setObjectName(transfer.getObjectName());
        }
        if (transfer.getRemotePath() != null) {
            tib.setRemotePath(transfer.getRemotePath());
        }

        // Best effort, the entity may no longer be configured, in which case
        // we can only return the ID.
        if (transfer.getLocalEntityId() != null) {
            tib.setLocalEntity(findLocalEntityInfo(service, transfer.getLocalEntityId()));
        }
        if (transfer.getRemoteEntityId() != null) {
            tib.setRemoteEntity(findRemoteEntityInfo(service, transfer.getRemoteEntityId()));
        }

        if (transfer instanceof CfdpFileTransfer) {
            CfdpTransactionId txid = ((CfdpFileTransfer) transfer).getTransactionId();
            if (txid != null) {// queued transfers do not have a transaction id
                tib.setTransactionId(toTransactionId(txid));
            }
        }

        if (transfer.getStartTime() != TimeEncoding.INVALID_INSTANT) {
            tib.setStartTime(TimeEncoding.toProtobufTimestamp(transfer.getStartTime()));
        }

        // creation time should always be there in the current code but in older versions this didn't exist
        if (transfer.getCreationTime() != TimeEncoding.INVALID_INSTANT) {
            tib.setCreationTime(TimeEncoding.toProtobufTimestamp(transfer.getCreationTime()));
        }

        String failureReason = transfer.getFailuredReason();
        if (failureReason != null) {
            tib.setFailureReason(failureReason);
        }

        if (transfer.getTransferType() != null) {
            tib.setTransferType(transfer.getTransferType());
        }

        return tib.build();
    }

    private static EntityInfo findLocalEntityInfo(FileTransferService service, long entityId) {
        for (var entityInfo : service.getLocalEntities()) {
            if (entityId == entityInfo.getId()) {
                return entityInfo;
            }
        }
        return EntityInfo.newBuilder().setId(entityId).build();
    }

    private static EntityInfo findRemoteEntityInfo(FileTransferService service, long entityId) {
        for (var entityInfo : service.getRemoteEntities()) {
            if (entityId == entityInfo.getId()) {
                return entityInfo;
            }
        }
        return EntityInfo.newBuilder().setId(entityId).build();
    }

    private static TransactionId toTransactionId(CfdpTransactionId id) {
        return TransactionId.newBuilder().setInitiatorEntity(id.getInitiatorEntity())
                .setSequenceNumber(id.getSequenceNumber()).build();
    }

    private FileTransferService verifyService(String yamcsInstance, String serviceName) throws NotFoundException {
        String instance = InstancesApi.verifyInstance(yamcsInstance);
        FileTransferService ftServ = null;
        if (serviceName != null) {
            ftServ = YamcsServer.getServer().getInstance(instance)
                    .getService(FileTransferService.class, serviceName);
        } else {
            List<FileTransferService> cl = YamcsServer.getServer().getInstance(instance)
                    .getServices(FileTransferService.class);
            if (cl.size() > 0) {
                ftServ = cl.get(0);
            }
        }
        if (ftServ == null) {
            if (serviceName == null) {
                throw new NotFoundException("No file transfer service found");
            } else {
                throw new NotFoundException("File transfer service '" + serviceName + "' not found");
            }
        }
        return ftServ;
    }
}
