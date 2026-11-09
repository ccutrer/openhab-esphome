/**
 * Copyright (c) 2023 Contributors to the Seime Openhab Addons project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package no.seime.openhab.binding.esphome.internal.discovery;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.common.ThreadPoolManager;
import org.openhab.core.io.transport.mdns.MDNSClient;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;

/**
 * Builds a cache of ESPHome device hostname -> IP mappings from mDNS advertisements
 * received via the standard openHAB {@link MDNSClient}, and supplies active lookups
 * on each network interface after a cache miss, racing against DNS resolution
 * with a timeout. Used to resolve hostnames when
 * OS-level DNS/mDNS is unreliable or slow to pick up address changes.
 */
@Component(immediate = true, service = ESPHomeHostnameResolver.class)
@NonNullByDefault
public class ESPHomeHostnameResolver implements ServiceListener {

    private static final Logger logger = LoggerFactory.getLogger(ESPHomeHostnameResolver.class);
    private static final String SERVICE_TYPE = "_esphomelib._tcp.local.";
    private static final String LOCAL_SUFFIX = ".local";

    private final Map<String, String> hostnameToIp = new ConcurrentHashMap<>();
    private final MDNSClient mdnsClient;
    private final ExecutorService executor;

    HostnameResolver hostnameResolver = InetAddress::getByName;

    @Activate
    public ESPHomeHostnameResolver(@Reference MDNSClient mdnsClient) {
        this(mdnsClient, ThreadPoolManager.getPool("esphome"));
    }

    ESPHomeHostnameResolver(MDNSClient mdnsClient, ExecutorService executor) {
        this.executor = executor;
        this.mdnsClient = mdnsClient;
        for (ServiceInfo info : mdnsClient.list(SERVICE_TYPE)) {
            update(info);
        }
        mdnsClient.addServiceListener(SERVICE_TYPE, this);
        logger.debug("Started mDNS hostname resolver, initial cache size: {}", hostnameToIp.size());
    }

    @Deactivate
    public void deactivate() {
        mdnsClient.removeServiceListener(SERVICE_TYPE, this);
        hostnameToIp.clear();
    }

    @Override
    public void serviceAdded(@Nullable ServiceEvent event) {
        // Address information not yet available; wait for serviceResolved.
    }

    @Override
    public void serviceRemoved(@Nullable ServiceEvent event) {
        if (event == null) {
            return;
        }
        ServiceInfo info = event.getInfo();
        if (info == null) {
            return;
        }
        String server = normalize(info.getServer());
        if (server != null) {
            String removed = hostnameToIp.remove(server);
            if (removed != null) {
                logger.debug("Removed mDNS cache entry {} -> {}", server, removed);
            }
        }
    }

    @Override
    public void serviceResolved(@Nullable ServiceEvent event) {
        if (event == null) {
            return;
        }
        update(event.getInfo());
    }

    private void update(@Nullable ServiceInfo info) {
        if (info == null) {
            return;
        }
        String server = normalize(info.getServer());
        if (server == null) {
            return;
        }
        Inet4Address[] ipv4 = info.getInet4Addresses();
        if (ipv4 == null || ipv4.length == 0) {
            return;
        }
        String ip = ipv4[0].getHostAddress();
        String prev = hostnameToIp.put(server, ip);
        if (!ip.equals(prev)) {
            logger.debug("Cached mDNS entry {} -> {} (was {})", server, ip, prev);
        }
    }

    /**
     * Resolve a hostname from the mDNS cache. The lookup is case-insensitive and tolerates
     * missing/present {@code .local} suffix.
     */
    public Optional<String> resolveCached(String hostname) {
        String normalized = normalize(hostname);
        if (normalized == null) {
            return Optional.empty();
        }
        String ip = hostnameToIp.get(normalized);
        if (ip == null) {
            if (normalized.endsWith(LOCAL_SUFFIX)) {
                ip = hostnameToIp.get(normalized.substring(0, normalized.length() - LOCAL_SUFFIX.length()));
            } else {
                ip = hostnameToIp.get(normalized + LOCAL_SUFFIX);
            }
        }
        return Optional.ofNullable(ip);
    }

    /**
     * Create blocking mDNS lookups for execution in parallel, one per network interface.
     * A cached address can be returned immediately without waiting for advertisements.
     */
    private List<Callable<InetAddress>> getMdnsResolutionTasks(String hostname, int resolveTimeoutSeconds) {
        Optional<String> cached = resolveCached(hostname);
        if (cached.isPresent()) {
            return List.of(() -> InetAddress.getByName(cached.get()));
        }
        List<Callable<InetAddress>> tasks = new ArrayList<>();
        for (JmDNS jmDNS : mdnsClient.getClientInstances()) {
            tasks.add(() -> resolveLocalHostname(jmDNS, hostname, resolveTimeoutSeconds));
        }
        return tasks;
    }

    private InetAddress resolveLocalHostname(JmDNS jmDNS, String hostname, int resolveTimeoutSeconds)
            throws UnknownHostException {
        String normalizedHostname = normalize(hostname);
        if (normalizedHostname == null) {
            throw new UnknownHostException(hostname);
        }
        String bareHostname = normalizedHostname.endsWith(LOCAL_SUFFIX)
                ? normalizedHostname.substring(0, normalizedHostname.length() - LOCAL_SUFFIX.length())
                : normalizedHostname;
        ServiceInfo[] services = jmDNS.list(SERVICE_TYPE, TimeUnit.SECONDS.toMillis(resolveTimeoutSeconds));
        for (ServiceInfo service : services) {
            if (matchesMdnsService(service, normalizedHostname, bareHostname)) {
                InetAddress address = service.getInet4Address();
                if (address == null) {
                    address = service.getInetAddress();
                }
                if (address != null) {
                    return address;
                }
            }
        }
        throw new UnknownHostException(hostname);
    }

    private boolean matchesMdnsService(ServiceInfo service, String normalizedHostname, String bareHostname) {
        return normalizedHostname.equals(normalize(service.getServer()))
                || bareHostname.equals(normalize(service.getName()));
    }

    private static @Nullable String normalize(@Nullable String hostname) {
        if (hostname == null || hostname.isEmpty()) {
            return null;
        }
        String normalized = hostname.trim().toLowerCase(Locale.ROOT);
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isEmpty() ? null : normalized;
    }

    public CompletableFuture<InetAddress> resolve(String hostname, int resolveTimeoutSeconds) {
        List<Callable<InetAddress>> resolutionTasks = getResolutionTasks(hostname, resolveTimeoutSeconds);
        CompletableFuture<InetAddress> result = new CompletableFuture<>();
        List<Future<?>> futures = new ArrayList<>(resolutionTasks.size());
        AtomicInteger remainingTasks = new AtomicInteger(resolutionTasks.size());
        AtomicReference<UnknownHostException> unknownHostException = new AtomicReference<>();

        for (Callable<InetAddress> resolutionTask : resolutionTasks) {
            Future<?> future = executor.submit(() -> {
                if (result.isDone()) {
                    return;
                }
                resolveTask(hostname, resolutionTask, result, remainingTasks, unknownHostException);
            });
            futures.add(future);
        }

        result.orTimeout(resolveTimeoutSeconds, TimeUnit.SECONDS).whenComplete((address, error) -> {
            for (Future<?> future : futures) {
                if (!future.isDone()) {
                    future.cancel(true);
                }
            }
        });
        return result;
    }

    private void resolveTask(String hostname, Callable<InetAddress> resolutionTask,
            CompletableFuture<InetAddress> result, AtomicInteger remainingTasks,
            AtomicReference<UnknownHostException> unknownHostException) {
        try {
            InetAddress address = resolutionTask.call();
            result.complete(address);
        } catch (UnknownHostException e) {
            unknownHostException.set(e);
            if (remainingTasks.decrementAndGet() == 0) {
                result.completeExceptionally(unknownHostException.get());
            }
        } catch (Exception e) {
            result.completeExceptionally(new ProtocolAPIError("Error resolving hostname '" + hostname + "'", e));
        }
    }

    private List<Callable<InetAddress>> getResolutionTasks(String hostname, int resolveTimeoutSeconds) {
        String lowerHostname = hostname.toLowerCase(Locale.ROOT);
        String suffix = lowerHostname.endsWith(".local.") ? ".local."
                : lowerHostname.endsWith(".local") ? ".local" : "";
        if (suffix.isEmpty()) {
            return List.of(() -> hostnameResolver.resolve(hostname));
        }

        List<Callable<InetAddress>> resolutionTasks = new ArrayList<>();
        resolutionTasks.addAll(getMdnsResolutionTasks(hostname, resolveTimeoutSeconds));

        String bareHostname = hostname.substring(0, hostname.length() - suffix.length());
        if (StringUtils.isNotBlank(bareHostname)) {
            resolutionTasks.add(() -> hostnameResolver.resolve(bareHostname));
        }
        if (resolutionTasks.isEmpty()) {
            return List.of(() -> hostnameResolver.resolve(hostname));
        }
        return resolutionTasks;
    }

    @FunctionalInterface
    interface HostnameResolver {
        InetAddress resolve(String hostname) throws UnknownHostException;
    }
}
