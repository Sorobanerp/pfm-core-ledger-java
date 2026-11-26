package br.com.pfm.ledger.adapter.in.grpc;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerServiceDefinition;
import io.grpc.protobuf.ProtoFileDescriptorSupplier;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.protobuf.services.ProtoReflectionService;
import io.grpc.stub.ServerCalls;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Starts a native gRPC HTTP/2 Server on {@code grpc.server.port} (default 9090 in profile {@code local})
 * with gRPC Server Reflection ({@link ProtoReflectionService}) enabled so engineers can inspect and invoke
 * {@code pfm.v1.CoreLedgerService}, {@code pfm.v1.UserProfileService}, and {@code pfm.v1.TenantAdminService}
 * directly with {@code grpcurl -plaintext localhost:9090 list}.
 */
@Component
@ConditionalOnProperty(name = "grpc.server.reflection-service-enabled", havingValue = "true")
public class LocalGrpcReflectionServer {

    private static final Logger log = LoggerFactory.getLogger(LocalGrpcReflectionServer.class);

    private final int grpcPort;
    private Server server;

    public LocalGrpcReflectionServer(@Value("${grpc.server.port:9090}") int grpcPort) {
        this.grpcPort = grpcPort;
    }

    @PostConstruct
    public void start() throws IOException, Descriptors.DescriptorValidationException {
        if (grpcPort <= 0) {
            return;
        }
        HealthStatusManager healthManager = new HealthStatusManager();
        Descriptors.FileDescriptor fileDesc = buildCoreLedgerProtoDescriptor();

        ServerBuilder<?> builder = ServerBuilder.forPort(grpcPort)
                .addService(ProtoReflectionService.newInstance())
                .addService(healthManager.getHealthService())
                .addService(buildDynamicService(fileDesc, "CoreLedgerService", "GetTenantFinancialSummary", "ProcessReceipt"))
                .addService(buildDynamicService(fileDesc, "UserProfileService", "GetUserProfile"))
                .addService(buildDynamicService(fileDesc, "TenantAdminService", "ListTenants", "CreateTenant"));

        this.server = builder.build().start();
        log.info("PFM Core Ledger gRPC Server started on port {} (Server Reflection enabled)", this.server.getPort());
    }

    public int getListeningPort() {
        return server != null ? server.getPort() : -1;
    }

    @PreDestroy
    public void stop() {
        if (this.server != null) {
            this.server.shutdownNow();
        }
    }

    private Descriptors.FileDescriptor buildCoreLedgerProtoDescriptor()
            throws Descriptors.DescriptorValidationException {
        DescriptorProtos.DescriptorProto reqMsg = DescriptorProtos.DescriptorProto.newBuilder()
                .setName("LedgerRpcRequest")
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("tenant_id")
                        .setNumber(1)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
                        .build())
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("reference_month")
                        .setNumber(2)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
                        .build())
                .build();

        DescriptorProtos.DescriptorProto respMsg = DescriptorProtos.DescriptorProto.newBuilder()
                .setName("LedgerRpcResponse")
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("status")
                        .setNumber(1)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
                        .build())
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("tenant_id")
                        .setNumber(2)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
                        .build())
                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                        .setName("service")
                        .setNumber(3)
                        .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
                        .build())
                .build();

        DescriptorProtos.FileDescriptorProto fileProto = DescriptorProtos.FileDescriptorProto.newBuilder()
                .setName("pfm/v1/core_ledger_service.proto")
                .setPackage("pfm.v1")
                .setSyntax("proto3")
                .addMessageType(reqMsg)
                .addMessageType(respMsg)
                .addService(buildServiceProto("CoreLedgerService", "GetTenantFinancialSummary", "ProcessReceipt"))
                .addService(buildServiceProto("UserProfileService", "GetUserProfile"))
                .addService(buildServiceProto("TenantAdminService", "ListTenants", "CreateTenant"))
                .build();

        return Descriptors.FileDescriptor.buildFrom(fileProto, new Descriptors.FileDescriptor[0]);
    }

    private DescriptorProtos.ServiceDescriptorProto buildServiceProto(String serviceName, String... methods) {
        DescriptorProtos.ServiceDescriptorProto.Builder sb = DescriptorProtos.ServiceDescriptorProto.newBuilder()
                .setName(serviceName);
        for (String m : methods) {
            sb.addMethod(DescriptorProtos.MethodDescriptorProto.newBuilder()
                    .setName(m)
                    .setInputType(".pfm.v1.LedgerRpcRequest")
                    .setOutputType(".pfm.v1.LedgerRpcResponse")
                    .build());
        }
        return sb.build();
    }

    private ServerServiceDefinition buildDynamicService(
            Descriptors.FileDescriptor fileDesc,
            String simpleServiceName,
            String... methods
    ) {
        String fullServiceName = "pfm.v1." + simpleServiceName;
        Descriptors.Descriptor reqDesc = fileDesc.findMessageTypeByName("LedgerRpcRequest");
        Descriptors.Descriptor respDesc = fileDesc.findMessageTypeByName("LedgerRpcResponse");

        io.grpc.ServiceDescriptor.Builder svcDescBuilder = io.grpc.ServiceDescriptor.newBuilder(fullServiceName)
                .setSchemaDescriptor((ProtoFileDescriptorSupplier) () -> fileDesc);

        java.util.List<MethodDescriptor<DynamicMessage, DynamicMessage>> methodDescs = new java.util.ArrayList<>();
        for (String m : methods) {
            MethodDescriptor<DynamicMessage, DynamicMessage> md = MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                    .setType(MethodDescriptor.MethodType.UNARY)
                    .setFullMethodName(MethodDescriptor.generateFullMethodName(fullServiceName, m))
                    .setRequestMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(reqDesc)))
                    .setResponseMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(respDesc)))
                    .build();
            svcDescBuilder.addMethod(md);
            methodDescs.add(md);
        }

        ServerServiceDefinition.Builder defBuilder = ServerServiceDefinition.builder(svcDescBuilder.build());
        for (MethodDescriptor<DynamicMessage, DynamicMessage> md : methodDescs) {
            defBuilder.addMethod(md, ServerCalls.asyncUnaryCall((req, observer) -> {
                String tenantId = String.valueOf(req.getField(reqDesc.findFieldByName("tenant_id")));
                if (tenantId.isEmpty()) {
                    tenantId = "11111111-1111-1111-1111-111111111111";
                }
                log.debug("{\"service\":\"pfm-core-ledger-java\",\"env\":\"local\",\"grpc.method\":\"{}\",\"tenant_id\":\"{}\",\"payload_preview\":\"{}\"}",
                        md.getFullMethodName(), tenantId, req.toString().replace("\n", " "));
                DynamicMessage resp = DynamicMessage.newBuilder(respDesc)
                        .setField(respDesc.findFieldByName("status"), "OK")
                        .setField(respDesc.findFieldByName("tenant_id"), tenantId)
                        .setField(respDesc.findFieldByName("service"), fullServiceName)
                        .build();
                observer.onNext(resp);
                observer.onCompleted();
            }));
        }
        return defBuilder.build();
    }
}
