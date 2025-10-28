/**
 * Copyright (c) 2023 Contributors to the Seime Openhab Addons project
 * <p>
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 * <p>
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 * <p>
 * SPDX-License-Identifier: EPL-2.0
 */
package no.seime.openhab.binding.esphome.internal.handler;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.events.AbstractEvent;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.thing.*;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.type.ChannelType;
import org.openhab.core.types.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.GeneratedMessage;
import com.jano7.executor.KeySequentialExecutor;

import io.esphome.api.*;
import no.seime.openhab.binding.esphome.events.ESPHomeEventFactory;
import no.seime.openhab.binding.esphome.internal.*;
import no.seime.openhab.binding.esphome.internal.LogLevel;
import no.seime.openhab.binding.esphome.internal.bluetooth.ESPHomeBluetoothProxyHandler;
import no.seime.openhab.binding.esphome.internal.comm.*;
import no.seime.openhab.binding.esphome.internal.message.*;
import no.seime.openhab.binding.esphome.internal.message.statesubscription.ESPHomeEventSubscriber;
import no.seime.openhab.binding.esphome.internal.message.statesubscription.EventSubscription;

/**
 * The {@link ESPHomeHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Arne Seime - Initial contribution
 */
@NonNullByDefault
public class ESPHomeHandler extends BaseThingHandler implements CommunicationListener {

    private static final int API_VERSION_MAJOR = 1;
    private static final int API_VERSION_MINOR = 9;
    private static final String DEVICE_LOGGER_NAME = "ESPHOMEDEVICE";
    private static final String ACTION_TAG_SCANNED = "esphome.tag_scanned";

    private final Logger logger = LoggerFactory.getLogger(ESPHomeHandler.class);
    private final Logger deviceLogger = LoggerFactory.getLogger(DEVICE_LOGGER_NAME);

    private final ConnectionSelector connectionSelector;
    private final ESPChannelTypeProvider dynamicChannelTypeProvider;
    private final ESPStateDescriptionProvider stateDescriptionProvider;
    private final Map<String, AbstractMessageHandler<? extends GeneratedMessage, ? extends GeneratedMessage>> commandTypeToHandlerMap = new HashMap<>();
    private final Map<Class<? extends GeneratedMessage>, AbstractMessageHandler<? extends GeneratedMessage, ? extends GeneratedMessage>> classToHandlerMap = new HashMap<>();
    private final List<Channel> dynamicChannels = new ArrayList<>();
    private final ESPHomeEventSubscriber eventSubscriber;
    private final MonitoredScheduledThreadPoolExecutor executorService;
    private final KeySequentialExecutor packetProcessor;
    private final EventPublisher eventPublisher;
    @Nullable
    private final String defaultEncryptionKey;
    private @Nullable ESPHomeConfiguration config;
    private @Nullable EncryptedFrameHelper frameHelper;
    @Nullable
    private ScheduledFuture<?> pingWatchdogFuture;
    @Nullable
    private ScheduledFuture<?> connectionTimeoutFuture;
    private Instant lastPong = Instant.now();
    @Nullable
    private ScheduledFuture<?> connectFuture;
    private final Object connectionStateLock = new Object();
    private ConnectionState connectionState = ConnectionState.UNINITIALIZED;
    private boolean disposed = false;
    private boolean interrogated;
    private boolean bluetoothProxyStarted = false;

    private String logPrefix;
    @Nullable
    private ESPHomeBluetoothProxyHandler espHomeBluetoothProxyHandler;

    public ESPHomeHandler(Thing thing, ConnectionSelector connectionSelector,
            ESPChannelTypeProvider dynamicChannelTypeProvider, ESPStateDescriptionProvider stateDescriptionProvider,
            ESPHomeEventSubscriber eventSubscriber, MonitoredScheduledThreadPoolExecutor executorService,
            KeySequentialExecutor packetProcessor, EventPublisher eventPublisher,
            @Nullable String defaultEncryptionKey) {
        super(thing);
        this.connectionSelector = connectionSelector;
        this.dynamicChannelTypeProvider = dynamicChannelTypeProvider;
        this.stateDescriptionProvider = stateDescriptionProvider;
        logPrefix = thing.getUID().getId();
        this.eventSubscriber = eventSubscriber;
        this.executorService = executorService;
        this.packetProcessor = packetProcessor;
        this.eventPublisher = eventPublisher;
        this.defaultEncryptionKey = defaultEncryptionKey;

        // Register message handlers for each type of message pairs
        registerMessageHandler(EntityTypes.SELECT, new SelectMessageHandler(this), ListEntitiesSelectResponse.class,
                SelectStateResponse.class);
        registerMessageHandler(EntityTypes.SENSOR, new SensorMessageHandler(this), ListEntitiesSensorResponse.class,
                SensorStateResponse.class);
        registerMessageHandler(EntityTypes.BINARY_SENSOR, new BinarySensorMessageHandler(this),
                ListEntitiesBinarySensorResponse.class, BinarySensorStateResponse.class);
        registerMessageHandler(EntityTypes.TEXT_SENSOR, new TextSensorMessageHandler(this),
                ListEntitiesTextSensorResponse.class, TextSensorStateResponse.class);
        registerMessageHandler(EntityTypes.TEXT, new TextMessageHandler(this), ListEntitiesTextResponse.class,
                TextStateResponse.class);
        registerMessageHandler(EntityTypes.SWITCH, new SwitchMessageHandler(this), ListEntitiesSwitchResponse.class,
                SwitchStateResponse.class);
        registerMessageHandler(EntityTypes.CLIMATE, new ClimateMessageHandler(this), ListEntitiesClimateResponse.class,
                ClimateStateResponse.class);
        registerMessageHandler(EntityTypes.NUMBER, new NumberMessageHandler(this), ListEntitiesNumberResponse.class,
                NumberStateResponse.class);
        registerMessageHandler(EntityTypes.LIGHT, new LightMessageHandler(this), ListEntitiesLightResponse.class,
                LightStateResponse.class);
        registerMessageHandler(EntityTypes.BUTTON, new ButtonMessageHandler(this), ListEntitiesButtonResponse.class,
                ButtonCommandRequest.class);
        registerMessageHandler(EntityTypes.COVER, new CoverMessageHandler(this), ListEntitiesCoverResponse.class,
                CoverStateResponse.class);
        registerMessageHandler(EntityTypes.FAN, new FanMessageHandler(this), ListEntitiesFanResponse.class,
                FanStateResponse.class);
        registerMessageHandler(EntityTypes.DATE, new DateMessageHandler(this), ListEntitiesDateResponse.class,
                DateStateResponse.class);
        registerMessageHandler(EntityTypes.DATE_TIME, new DateTimeMessageHandler(this),
                ListEntitiesDateTimeResponse.class, DateTimeStateResponse.class);
        registerMessageHandler(EntityTypes.TIME, new TimeMessageHandler(this), ListEntitiesTimeResponse.class,
                TimeStateResponse.class);
        registerMessageHandler(EntityTypes.LOCK, new LockMessageHandler(this), ListEntitiesLockResponse.class,
                LockStateResponse.class);
    }

    private void registerMessageHandler(String entityType,
            AbstractMessageHandler<? extends GeneratedMessage, ? extends GeneratedMessage> messageHandler,
            Class<? extends GeneratedMessage> listEntitiesClass, Class<? extends GeneratedMessage> stateClass) {

        commandTypeToHandlerMap.put(entityType, messageHandler);
        classToHandlerMap.put(listEntitiesClass, messageHandler);
        classToHandlerMap.put(stateClass, messageHandler);
    }

    @Override
    public void initialize() {
        disposed = false;
        logger.debug("[{}] Initializing ESPHome handler", thing.getUID());
        config = getConfigAs(ESPHomeConfiguration.class);

        // Use configured logprefix instead of default thingId
        if (config.logPrefix != null && !config.logPrefix.isEmpty()) {
            logPrefix = String.format("%s", config.logPrefix); // To avoid nullness warning
        }

        if (config.hostname != null && !config.hostname.isEmpty()) {
            scheduleConnect(0);
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "No hostname configured");
        }
    }

    @Override
    public void dispose() {
        synchronized (connectionStateLock) {
            disposed = true;
            eventSubscriber.removeEventSubscriptions(this);
            stateDescriptionProvider.removeDescriptionsForThing(thing.getUID());
            cancelConnectFuture();
            cancelPingWatchdog();
            cancelConnectionTimeoutWatchdog();
            if (frameHelper != null) {
                if (connectionState == ConnectionState.CONNECTED) {
                    try {
                        frameHelper.send(DisconnectRequest.getDefaultInstance());
                    } catch (ProtocolAPIError e) {
                        // Quietly ignore
                    }
                }
                // ALWAYS close the connection to ensure the socket and I/O threads
                // are terminated and resources are released.
                frameHelper.close();
                frameHelper = null;
            }
            connectionState = ConnectionState.UNINITIALIZED;
        }
        super.dispose();
    }

    @Override
    public void handleRemoval() {
        dynamicChannelTypeProvider.removeChannelTypesForThing(thing.getUID());
        super.handleRemoval();
    }

    private void connect() {
        synchronized (connectionStateLock) {
            try {
                if (disposed) {
                    return;
                }
                connectionState = ConnectionState.CONNECTING;

                dynamicChannels.clear();

                String hostname = config.hostname;
                int port = config.port;

                logger.info("[{}] Trying to connect to {}:{}", logPrefix, hostname, port);
                updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NONE,
                        String.format("Connecting to %s:%d", hostname, port));

                // Default to using the default encryption key from the binding if not set in device configuration
                String encryptionKey = config.encryptionKey;
                if (encryptionKey == null || encryptionKey.isEmpty()) {
                    if (defaultEncryptionKey != null) {
                        encryptionKey = defaultEncryptionKey;
                        logger.info("[{}] Using binding default encryption key", logPrefix);
                    } else {
                        logger.warn("[{}] No encryption key configured on neither binding nor thing. Cannot continue",
                                logPrefix);
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                                "No encryption key configured. Please set 'encryptionKey' in thing configuration or a default one in binding configuration");
                        return;
                    }
                }

                frameHelper = new EncryptedFrameHelper(connectionSelector, this, encryptionKey, config.deviceId,
                        logPrefix, packetProcessor);

                frameHelper.connect(hostname, port);

                cancelConnectionTimeoutWatchdog();
                connectionTimeoutFuture = executorService.schedule(() -> {
                    logger.warn("[{}] Connection attempt timed out after {} seconds.", logPrefix,
                            config.connectTimeout);
                    handleDisconnection(ThingStatusDetail.COMMUNICATION_ERROR, "Connection attempt timed out", true);
                }, config.connectTimeout, TimeUnit.SECONDS, String.format("[%s] Connection watchdog", logPrefix));

            } catch (ProtocolException e) {
                logger.warn("[{}] Error initial connection", logPrefix, e);
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
                scheduleConnect(config.reconnectInterval);
            }
        }
    }

    public void sendMessage(GeneratedMessage message) throws ProtocolAPIError {
        frameHelper.send(message);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        synchronized (connectionStateLock) {
            if (connectionState != ConnectionState.CONNECTED) {
                logger.warn("[{}] Not connected, ignoring command {}", logPrefix, command);
                return;
            }

            if (command == RefreshType.REFRESH) {
                try {
                    frameHelper.send(SubscribeStatesRequest.getDefaultInstance());
                } catch (ProtocolAPIError e) {
                    logger.error("[{}] Error sending command {} to channel {}: {}", logPrefix, command, channelUID,
                            e.getMessage());
                }
                return;
            }

            Optional<Channel> optionalChannel = thing.getChannels().stream().filter(e -> e.getUID().equals(channelUID))
                    .findFirst();
            optionalChannel.ifPresent(channel -> {
                try {
                    String entityType = (String) channel.getConfiguration()
                            .get(BindingConstants.CHANNEL_CONFIGURATION_ENTITY_TYPE);
                    if (entityType == null) {
                        logger.warn("[{}] No entity type configuration found for channel {}", logPrefix, channelUID);
                        return;
                    }

                    AbstractMessageHandler<? extends GeneratedMessage, ? extends GeneratedMessage> abstractMessageHandler = commandTypeToHandlerMap
                            .get(entityType);
                    if (abstractMessageHandler == null) {
                        logger.warn("[{}] No message handler for entity type {}", logPrefix, entityType);
                    } else {
                        int key = ((BigDecimal) channel.getConfiguration()
                                .get(BindingConstants.CHANNEL_CONFIGURATION_ENTITY_KEY)).intValue();
                        abstractMessageHandler.handleCommand(channel, command, key);
                    }

                } catch (Exception e) {
                    logger.error("[{}] Error sending command {} to channel {}: {}", logPrefix, command, channelUID,
                            e.getMessage(), e);
                }
            });
        }
    }

    @Override
    public void onConnect() throws ProtocolAPIError {
        synchronized (connectionStateLock) {
            cancelConnectionTimeoutWatchdog();
            logger.debug("[{}] Encrypted connection established. Starting API handshake.", logPrefix);
            HelloRequest helloRequest = HelloRequest.newBuilder().setClientInfo("openHAB")
                    .setApiVersionMajor(API_VERSION_MAJOR).setApiVersionMinor(API_VERSION_MINOR).build();
            connectionState = ConnectionState.HELLO_SENT;
            frameHelper.send(helloRequest);
            // Send this at the same time; no need to wait
            frameHelper.send(ConnectRequest.getDefaultInstance());
        }
    }

    @Override
    public void onPacket(@NonNull GeneratedMessage message) {
        synchronized (connectionStateLock) {
            try {
                switch (connectionState) {
                    case UNINITIALIZED -> logger.debug(
                            "[{}] Received packet {} while uninitialized, this can happen when the socket is closed while unprocessed packets exists. Ignoring",
                            logPrefix, message.getClass().getSimpleName());
                    case CONNECTING -> {
                        // We are still connecting, so we ignore any packets
                        logger.debug("[{}] Received packet {} while connecting, ignoring", logPrefix,
                                message.getClass().getSimpleName());
                    }
                    case HELLO_SENT -> handleHelloResponse(message);
                    case CONNECTED -> handleConnected(message);
                }
            } catch (ProtocolAPIError e) {
                logger.warn("[{}] Error parsing packet", logPrefix, e);
                onParseError(CommunicationError.PACKET_ERROR);
            }
        }
    }

    @Override
    public void onEndOfStream(String message) {
        String reason = "ESPHome device abruptly closed connection: " + message;
        handleDisconnection(ThingStatusDetail.COMMUNICATION_ERROR, reason, true);
    }

    @Override
    public void onParseError(CommunicationError error) {
        handleDisconnection(ThingStatusDetail.COMMUNICATION_ERROR, error.toString(), true);
    }

    private void remoteDisconnect() {
        String reason = "ESPHome device requested disconnect";
        handleDisconnection(ThingStatusDetail.NONE, reason, true);
    }

    private void handleDisconnection(ThingStatusDetail detail, String message, boolean scheduleReconnect) {
        synchronized (connectionStateLock) {
            if (connectionState == ConnectionState.UNINITIALIZED || disposed) {
                return;
            }

            String finalMessage = message;
            if (scheduleReconnect) {
                finalMessage = String.format("%s. Will reconnect in %d seconds", message, config.reconnectInterval);
            }

            logger.warn("[{}] Disconnecting. Reason: {}", logPrefix, finalMessage);
            updateStatus(ThingStatus.OFFLINE, detail, finalMessage);

            eventSubscriber.removeEventSubscriptions(this);
            cancelPingWatchdog();
            cancelConnectionTimeoutWatchdog();

            if (frameHelper != null) {
                frameHelper.close();
                frameHelper = null;
            }

            connectionState = ConnectionState.UNINITIALIZED;

            if (scheduleReconnect) {
                scheduleConnect(config.reconnectInterval);
            }
        }
    }

    private void handleConnected(GeneratedMessage message) throws ProtocolAPIError {
        if (logger.isDebugEnabled()) {
            // ToString method costs a bit
            logger.debug("[{}] Received message type {} with content '{}'", logPrefix,
                    message.getClass().getSimpleName(), StringUtils.trimToEmpty(message.toString()));
        }
        if (disposed) {
            return;
        }

        if (message instanceof ConnectResponse connectResponse) {
            if (connectResponse.getInvalidPassword()) {
                logger.debug("[{}] Received login response {}", logPrefix, connectResponse);

                handleDisconnection(ThingStatusDetail.CONFIGURATION_ERROR, "Invalid password", false);
            }
            return;
        }

        if (message instanceof DeviceInfoResponse rsp) {
            Map<String, String> props = new HashMap<>();
            props.put(Thing.PROPERTY_FIRMWARE_VERSION, rsp.getEsphomeVersion());
            props.put(Thing.PROPERTY_MAC_ADDRESS, rsp.getMacAddress());
            props.put(Thing.PROPERTY_MODEL_ID, rsp.getModel());
            props.put("name", rsp.getName());
            props.put(Thing.PROPERTY_VENDOR, rsp.getManufacturer());
            props.put("compilationTime", rsp.getCompilationTime());
            if (!rsp.getProjectName().isEmpty()) {
                props.put("projectName", rsp.getProjectName());
            }
            if (!rsp.getProjectVersion().isEmpty()) {
                props.put("projectVersion", rsp.getProjectVersion());
            }
            updateThing(editThing().withProperties(props).build());
        } else if (message instanceof ListEntitiesDoneResponse) {
            updateThing(editThing().withChannels(dynamicChannels).build());
            logger.debug("[{}] Device interrogation complete, done updating thing channels", logPrefix);
            interrogated = true;
            frameHelper.send(SubscribeStatesRequest.getDefaultInstance());
        } else if (message instanceof PingRequest) {
            logger.debug("[{}] Responding to ping request", logPrefix);
            frameHelper.send(PingResponse.getDefaultInstance());
        } else if (message instanceof PingResponse) {
            logger.debug("[{}] Received ping response", logPrefix);
            lastPong = Instant.now();
        } else if (message instanceof DisconnectRequest) {
            frameHelper.send(DisconnectResponse.getDefaultInstance());
            remoteDisconnect();
        } else if (message instanceof DisconnectResponse) {
            if (frameHelper != null) {
                frameHelper.close();
                frameHelper = null;
            }
        } else if (message instanceof SubscribeLogsResponse subscribeLogsResponse) {
            deviceLogger.info("[{}] {}", logPrefix, subscribeLogsResponse.getMessage().toStringUtf8());
        } else if (message instanceof HomeassistantServiceResponse serviceResponse) {
            Map<String, String> data = convertPbListToMap(serviceResponse.getDataList());
            Map<String, String> dataTemplate = convertPbListToMap(serviceResponse.getDataTemplateList());
            Map<String, String> variables = convertPbListToMap(serviceResponse.getVariablesList());
            AbstractEvent event;
            if (serviceResponse.getIsEvent()) {
                String tagId;
                if (serviceResponse.getService().equals(ACTION_TAG_SCANNED) && dataTemplate.isEmpty()
                        && variables.isEmpty() && data.size() == 1 && (tagId = data.get("tag_id")) != null) {
                    event = ESPHomeEventFactory.createTagScannedEvent(config.deviceId, tagId);
                } else {
                    event = ESPHomeEventFactory.createEventEvent(config.deviceId, serviceResponse.getService(), data,
                            dataTemplate, variables);
                }
            } else {
                event = ESPHomeEventFactory.createActionEvent(config.deviceId, serviceResponse.getService(), data,
                        dataTemplate, variables);
            }
            eventPublisher.post(event);
        } else if (message instanceof SubscribeHomeAssistantStateResponse subscribeHomeAssistantStateResponse) {
            initializeStateSubscription(subscribeHomeAssistantStateResponse);
        } else if (message instanceof GetTimeRequest) {
            logger.debug("[{}] Received time sync request", logPrefix);
            GetTimeResponse getTimeResponse = GetTimeResponse.newBuilder()
                    .setEpochSeconds((int) (System.currentTimeMillis() / 1000)).build();
            frameHelper.send(getTimeResponse);
        } else if (message instanceof BluetoothLEAdvertisementResponse
                || message instanceof BluetoothLERawAdvertisementsResponse
                || message instanceof BluetoothDeviceConnectionResponse
                || message instanceof BluetoothGATTGetServicesResponse
                || message instanceof BluetoothGATTGetServicesDoneResponse
                || message instanceof BluetoothGATTReadResponse || message instanceof BluetoothGATTNotifyDataResponse
                || message instanceof BluetoothConnectionsFreeResponse || message instanceof BluetoothGATTErrorResponse
                || message instanceof BluetoothGATTWriteResponse || message instanceof BluetoothGATTNotifyResponse
                || message instanceof BluetoothDevicePairingResponse
                || message instanceof BluetoothDeviceUnpairingResponse
                || message instanceof BluetoothDeviceClearCacheResponse
                || message instanceof BluetoothScannerStateResponse) {
            if (espHomeBluetoothProxyHandler != null) {
                espHomeBluetoothProxyHandler.handleBluetoothMessage(message, this);
            }
        } else {
            // Regular messages handled by message handlers
            AbstractMessageHandler<? extends GeneratedMessage, ? extends GeneratedMessage> abstractMessageHandler = classToHandlerMap
                    .get(message.getClass());
            if (abstractMessageHandler != null) {
                abstractMessageHandler.handleMessage(message);
            } else {
                logger.warn("[{}] Unhandled message of type {}. This is lack of support in the binding. Content: '{}'.",
                        logPrefix, message.getClass().getName(), message);
            }
        }
    }

    public void sendBluetoothCommand(GeneratedMessage message) {
        synchronized (connectionStateLock) {
            try {
                if (connectionState == ConnectionState.CONNECTED) {
                    frameHelper.send(message);
                } else {
                    logger.warn("[{}] Not connected, ignoring bluetooth command {}", logPrefix, message);
                }
            } catch (ProtocolAPIError e) {
                logger.error("[{}] Error sending bluetooth command", logPrefix, e);
            }
        }
    }

    private void initializeStateSubscription(SubscribeHomeAssistantStateResponse rsp) {
        // Setup event subscriber
        logger.debug("[{}] Start subscribe to OH events entity: {}, attribute: {}", logPrefix, rsp.getEntityId(),
                rsp.getAttribute());

        EventSubscription subscription = eventSubscriber.createEventSubscription(rsp.getEntityId(), rsp.getAttribute(),
                this);
        eventSubscriber.addEventSubscription(this, subscription);

        String state = eventSubscriber.getInitialState(logPrefix, subscription);

        logger.debug("[{}] Sending initial state for subscription {} with state '{}'", logPrefix, subscription, state);

        HomeAssistantStateResponse ohStateUpdate = HomeAssistantStateResponse.newBuilder()
                .setEntityId(subscription.getEntityId()).setAttribute(subscription.getAttribute()).setState(state)
                .build();
        try {
            frameHelper.send(ohStateUpdate);
        } catch (ProtocolAPIError e) {
            logger.warn("[{}] Error sending OpenHAB state update to ESPHome", logPrefix, e);
        }
    }

    public void handleOpenHABEvent(EventSubscription subscription, String esphomeState) {
        synchronized (connectionStateLock) {
            if (disposed || connectionState != ConnectionState.CONNECTED) {
                logger.debug("[{}] Not connected, skipping OpenHAB event for {}", logPrefix,
                        subscription.getEntityId());
                return;
            }
            HomeAssistantStateResponse ohStateUpdate = HomeAssistantStateResponse.newBuilder()
                    .setEntityId(subscription.getEntityId()).setAttribute(subscription.getAttribute())
                    .setState(esphomeState).build();
            try {
                frameHelper.send(ohStateUpdate);
            } catch (ProtocolAPIError e) {
                logger.warn("[{}] Error sending OpenHAB state update to ESPHome", logPrefix, e);
            }
        }
    }

    @Override
    public void updateState(ChannelUID channelUID, State state) {
        super.updateState(channelUID, state);
    }

    private void handleHelloResponse(GeneratedMessage message) throws ProtocolAPIError {
        if (message instanceof HelloResponse helloResponse) {
            synchronized (connectionStateLock) {
                logger.debug("[{}] Received hello response {}", logPrefix, helloResponse);
                logger.info(
                        "[{}] API handshake successful. Device '{}' running '{}' on protocol version '{}.{}'. Logging in.",
                        logPrefix, helloResponse.getName(), helloResponse.getServerInfo(),
                        helloResponse.getApiVersionMajor(), helloResponse.getApiVersionMinor());
                connectionState = ConnectionState.CONNECTED;

                if (config.allowActions) {
                    logger.debug("[{}] Requesting device to send actions and events", logPrefix);
                    frameHelper.send(SubscribeHomeassistantServicesRequest.getDefaultInstance());
                }
                if (config.deviceLogLevel != LogLevel.NONE) {
                    logger.info("[{}] Starting to stream logs to logger " + DEVICE_LOGGER_NAME, logPrefix);

                    frameHelper.send(SubscribeLogsRequest.newBuilder()
                            .setLevel(io.esphome.api.LogLevel.valueOf("LOG_LEVEL_" + config.deviceLogLevel.name()))
                            .build());
                }

                updateStatus(ThingStatus.ONLINE);
                logger.debug("[{}] Device login complete, starting device interrogation", logPrefix);
                // Reset last pong
                lastPong = Instant.now();

                pingWatchdogFuture = executorService.scheduleAtFixedRate(() -> {
                    synchronized (connectionStateLock) {
                        if (lastPong.plusSeconds((long) config.maxPingTimeouts * config.pingInterval)
                                .isBefore(Instant.now())) {
                            logger.warn(
                                    "[{}] Ping responses lacking. Waited {} times {}s, total of {}s. Last pong received at {}. Assuming connection lost and disconnecting",
                                    logPrefix, config.maxPingTimeouts, config.pingInterval,
                                    config.maxPingTimeouts * config.pingInterval, lastPong);

                            String reason = String.format(
                                    "ESPHome did not respond to ping requests. %d pings sent with %d s delay",
                                    config.maxPingTimeouts, config.pingInterval);
                            handleDisconnection(ThingStatusDetail.COMMUNICATION_ERROR, reason, true);
                        } else {
                            if (connectionState == ConnectionState.CONNECTED) {
                                try {
                                    logger.debug("[{}] Sending ping", logPrefix);
                                    frameHelper.send(PingRequest.getDefaultInstance());
                                } catch (ProtocolAPIError e) {
                                    logger.warn("[{}] Error sending ping request", logPrefix, e);
                                }
                            }
                        }
                    }
                }, config.pingInterval, config.pingInterval, TimeUnit.SECONDS,
                        String.format("[%s] Ping watchdog", logPrefix));

                // Start interrogation
                frameHelper.send(DeviceInfoRequest.getDefaultInstance());
                frameHelper.send(ListEntitiesRequest.getDefaultInstance());
                frameHelper.send(SubscribeHomeAssistantStatesRequest.getDefaultInstance());
            }
        }
    }

    public void addChannelType(ChannelType channelType) {
        dynamicChannelTypeProvider.putChannelType(channelType);
    }

    public void addDescription(ChannelUID channelUID, StateDescription stateDescription) {
        stateDescriptionProvider.setDescription(channelUID, stateDescription);
    }

    public void addDescription(ChannelUID channelUID, CommandDescription commandDescription) {
        stateDescriptionProvider.setDescription(channelUID, commandDescription);
    }

    public void addChannel(Channel channel) {
        dynamicChannels.add(channel);
    }

    public boolean isDisposed() {
        return disposed;
    }

    public void listenForBLEAdvertisements(ESPHomeBluetoothProxyHandler espHomeBluetoothProxyHandler) {
        synchronized (connectionStateLock) {
            this.espHomeBluetoothProxyHandler = espHomeBluetoothProxyHandler;
            if (config.enableBluetoothProxy && !bluetoothProxyStarted && connectionState == ConnectionState.CONNECTED) {
                try {
                    frameHelper.send(SubscribeBluetoothLEAdvertisementsRequest.getDefaultInstance());
                    bluetoothProxyStarted = true;
                } catch (Exception e) {
                    logger.error("[{}] Error starting BLE proxy", logPrefix, e);
                }
            }
        }
    }

    public void stopListeningForBLEAdvertisements() {
        synchronized (connectionStateLock) {
            if (connectionState == ConnectionState.CONNECTED) {
                try {
                    frameHelper.send(UnsubscribeBluetoothLEAdvertisementsRequest.getDefaultInstance());
                } catch (Exception e) {
                    logger.warn("[{}] Error stopping BLE proxy", logPrefix, e);
                }
            }

            bluetoothProxyStarted = false;
            espHomeBluetoothProxyHandler = null;
        }
    }

    private void cancelPingWatchdog() {
        if (pingWatchdogFuture != null) {
            pingWatchdogFuture.cancel(true);
            pingWatchdogFuture = null;
        }
    }

    private void cancelConnectFuture() {
        if (connectFuture != null) {
            connectFuture.cancel(true);
            connectFuture = null;
        }
    }

    private void cancelConnectionTimeoutWatchdog() {
        if (connectionTimeoutFuture != null) {
            connectionTimeoutFuture.cancel(true);
            connectionTimeoutFuture = null;
        }
    }

    private void scheduleConnect(int delaySeconds) {
        cancelConnectFuture();
        connectFuture = executorService.schedule(this::connect, delaySeconds, TimeUnit.SECONDS,
                String.format("[%s] Connect", logPrefix), 7000);
    }

    public boolean isInterrogated() {
        return interrogated;
    }

    public List<Channel> getDynamicChannels() {
        return dynamicChannels;
    }

    public String getLogPrefix() {
        return logPrefix;
    }

    private Map<String, String> convertPbListToMap(List<HomeassistantServiceMap> list) {
        Map<String, String> map = new HashMap<>();
        for (HomeassistantServiceMap kv : list) {
            map.put(kv.getKey(), kv.getValue());
        }
        return Collections.unmodifiableMap(map);
    }

    private enum ConnectionState {
        // Initial state, no connection
        UNINITIALIZED,
        // TCP connect ongoing
        CONNECTING,
        // TCP connected to ESPHome, first handshake sent
        HELLO_SENT,

        // Connection established
        CONNECTED

    }
}
