/*******************************************************************************
 * Copyright (c) 2016, 2020 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/

package org.eclipse.hono.service.tenant;

import java.net.HttpURLConnection;
import java.util.Objects;

import javax.security.auth.x500.X500Principal;

import org.eclipse.hono.client.ClientErrorException;
import org.eclipse.hono.service.EventBusService;
import org.eclipse.hono.tracing.TracingHelper;
import org.eclipse.hono.util.EventBusMessage;
import org.eclipse.hono.util.MessageHelper;
import org.eclipse.hono.util.RegistryManagementConstants;
import org.eclipse.hono.util.TenantConstants;

import io.opentracing.Span;
import io.vertx.core.Future;
import io.vertx.core.Verticle;
import io.vertx.core.json.JsonObject;


/**
 * Adapter to bind {@link TenantService} to the vertx event bus.
 * <p>
 * In particular, this base class provides support for receiving service invocation request messages
 * via vert.x' event bus and routing them to specific methods accepting the
 * query parameters contained in the request message.
 * @deprecated This class will be removed in future versions as AMQP endpoint does not use event bus anymore.
 *             Please use {@link org.eclipse.hono.service.tenant.AbstractTenantAmqpEndpoint} based implementation in the future.
 */
@Deprecated
public abstract class EventBusTenantAdapter extends EventBusService implements Verticle {

    private static final String SPAN_NAME_GET_TENANT = "get Tenant";

    private static final String TAG_SUBJECT_DN_NAME = "subject_dn_name";

    /**
     * The service to forward requests to.
     * 
     * @return The service to bind to, must never return {@code null}.
     */
    protected abstract TenantService getService();

    @Override
    protected final String getEventBusAddress() {
        return RegistryManagementConstants.EVENT_BUS_ADDRESS_TENANT_IN;
    }

    /**
     * Processes a Tenant API request message received via the vert.x event bus.
     * <p>
     * This method validates the request payload against the Tenant API specification
     * before invoking the corresponding {@code TenantService} methods.
     * 
     * @param request The request message.
     * @return A future indicating the outcome of the service invocation.
     * @throws NullPointerException If the request message is {@code null}.
     */
    @Override
    public Future<EventBusMessage> processRequest(final EventBusMessage request) {

        Objects.requireNonNull(request);

        switch (TenantConstants.TenantAction.from(request.getOperation())) {
        case get:
            return processGetRequest(request);
        default:
            return processCustomTenantMessage(request);
        }
    }

    Future<EventBusMessage> processGetRequest(final EventBusMessage request) {

        final String tenantId = request.getTenant();
        final JsonObject payload = request.getJsonPayload();

        final Span span = TracingHelper.buildServerChildSpan(
                tracer,
                request.getSpanContext(),
                SPAN_NAME_GET_TENANT,
                getClass().getSimpleName()
        ).start();

        final Future<EventBusMessage> resultFuture;
        if (tenantId == null && payload == null) {
            TracingHelper.logError(span, "request does not contain any query parameters");
            log.debug("request does not contain any query parameters");
            resultFuture = Future.failedFuture(new ClientErrorException(HttpURLConnection.HTTP_BAD_REQUEST));

        } else if (tenantId != null) {

            // deprecated API
            log.debug("retrieving tenant [{}] using deprecated variant of get tenant request", tenantId);
            TracingHelper.TAG_TENANT_ID.set(span, tenantId);
            span.log("using deprecated variant of get tenant request");
            // span will be finished in processGetByIdRequest
            resultFuture = processGetByIdRequest(request, tenantId, span);

        } else {

            final String tenantIdFromPayload = getTypesafeValueForField(String.class, payload,
                    TenantConstants.FIELD_PAYLOAD_TENANT_ID);
            final String subjectDn = getTypesafeValueForField(String.class, payload,
                    TenantConstants.FIELD_PAYLOAD_SUBJECT_DN);

            if (tenantIdFromPayload == null && subjectDn == null) {
                TracingHelper.logError(span, "request does not contain any query parameters");
                log.debug("payload does not contain any query parameters");
                resultFuture = Future.failedFuture(new ClientErrorException(HttpURLConnection.HTTP_BAD_REQUEST));
            } else if (tenantIdFromPayload != null) {
                log.debug("retrieving tenant [id: {}]", tenantIdFromPayload);
                TracingHelper.TAG_TENANT_ID.set(span, tenantIdFromPayload);
                resultFuture = processGetByIdRequest(request, tenantIdFromPayload, span);
            } else {
                span.setTag(TAG_SUBJECT_DN_NAME, subjectDn);
                resultFuture = processGetByCaRequest(request, subjectDn, span);
            }
        }
        return finishSpanOnFutureCompletion(span, resultFuture);
    }

    private Future<EventBusMessage> processGetByIdRequest(final EventBusMessage request, final String tenantId,
            final Span span) {

        return getService().get(tenantId, span)
                .map(tr -> request.getResponse(tr.getStatus())
                        .setJsonPayload(tr.getPayload())
                        .setTenant(tenantId)
                        .setCacheDirective(tr.getCacheDirective()));
    }

    private Future<EventBusMessage> processGetByCaRequest(final EventBusMessage request, final String subjectDn,
            final Span span) {

        try {
            final X500Principal dn = new X500Principal(subjectDn);
            log.debug("retrieving tenant [subject DN: {}]", subjectDn);
            return getService().get(dn, span)
                    .map(tr -> {
                        final EventBusMessage response = request.getResponse(tr.getStatus())
                                .setJsonPayload(tr.getPayload())
                                .setCacheDirective(tr.getCacheDirective());
                        if (tr.isOk() && tr.getPayload() != null) {
                            final String tenantId = getTypesafeValueForField(String.class, tr.getPayload(),
                                    TenantConstants.FIELD_PAYLOAD_TENANT_ID);
                            span.setTag(MessageHelper.APP_PROPERTY_TENANT_ID, tenantId);
                            response.setTenant(tenantId);
                        }
                        return response;
                    });
        } catch (final IllegalArgumentException e) {
            TracingHelper.logError(span, "illegal subject DN provided by client: " + subjectDn);
            // the given subject DN is invalid
            log.debug("cannot parse subject DN [{}] provided by client", subjectDn);
            return Future.failedFuture(new ClientErrorException(HttpURLConnection.HTTP_BAD_REQUEST));
        }
    }

    /**
     * Processes a request for a non-standard operation.
     * <p>
     * Subclasses should override this method in order to support additional, custom
     * operations that are not defined by Hono's Tenant API.
     * <p>
     * This default implementation simply returns a future that is failed with a
     * {@link ClientErrorException} with an error code <em>400 Bad Request</em>.
     *
     * @param request The request to process.
     * @return A future indicating the outcome of the service invocation.
     */
    protected Future<EventBusMessage> processCustomTenantMessage(final EventBusMessage request) {
        log.debug("invalid operation in request message [{}]", request.getOperation());
        return Future.failedFuture(new ClientErrorException(HttpURLConnection.HTTP_BAD_REQUEST));
    }

}
