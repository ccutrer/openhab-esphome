package no.seime.openhab.binding.esphome.internal.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openhab.core.io.transport.mdns.MDNSClient;

@ExtendWith(MockitoExtension.class)
class ESPHomeHostnameResolverTest {
    @Mock
    private MDNSClient mdnsClient;

    private ExecutorService executor;
    private ESPHomeHostnameResolver resolver;

    @BeforeEach
    void setUp() {
        executor = Executors.newCachedThreadPool();
        when(mdnsClient.list("_esphomelib._tcp.local.")).thenReturn(new ServiceInfo[0]);
        resolver = new ESPHomeHostnameResolver(mdnsClient, executor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void timesOutHostnameResolutionUsingResolveTimeout() throws Exception {
        JmDNS jmDNS = org.mockito.Mockito.mock(JmDNS.class);
        when(mdnsClient.getClientInstances()).thenReturn(Set.of(jmDNS));
        when(jmDNS.list(eq("_esphomelib._tcp.local."), anyLong())).thenAnswer(invocation -> {
            TimeUnit.SECONDS.sleep(5);
            return new ServiceInfo[0];
        });
        setField("hostnameResolver", (ESPHomeHostnameResolver.HostnameResolver) hostname -> {
            try {
                TimeUnit.SECONDS.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return InetAddress.getByName("127.0.0.1");
        });

        long start = System.nanoTime();
        ExecutionException error = assertThrows(ExecutionException.class,
                () -> resolver.resolve("slow.local", 1).get());
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertEquals(TimeoutException.class, error.getCause().getClass());
        assertTrue(elapsedMillis < TimeUnit.SECONDS.toMillis(3));
    }

    @Test
    void resolvesBareHostnameInParallelForLocalHostname() throws Exception {
        assertResolvesBareHostnameInParallel("device.local", "device");
    }

    @Test
    void resolvesBareHostnameInParallelForLocalHostnameWithTrailingDot() throws Exception {
        assertResolvesBareHostnameInParallel("device.local.", "device");
    }

    @Test
    void doesNotResolveBareHostnameForNonLocalHostname() throws Exception {
        List<String> attemptedHostnames = Collections.synchronizedList(new ArrayList<>());
        setField("hostnameResolver", (ESPHomeHostnameResolver.HostnameResolver) hostname -> {
            attemptedHostnames.add(hostname);
            return InetAddress.getByName("127.0.0.1");
        });

        InetAddress address = resolver.resolve("device.example", 5).get();

        assertEquals("127.0.0.1", address.getHostAddress());
        assertEquals(List.of("device.example"), attemptedHostnames);
    }

    @Test
    void resolvesNonLocalHostnameWhenExecutorIsSaturated() throws Exception {
        CountDownLatch releaseExecutor = new CountDownLatch(1);
        CountDownLatch executorSaturated = new CountDownLatch(2);
        executor.execute(() -> {
            executorSaturated.countDown();
            awaitLatch(releaseExecutor);
        });
        executor.execute(() -> {
            executorSaturated.countDown();
            awaitLatch(releaseExecutor);
        });
        assertTrue(executorSaturated.await(1, TimeUnit.SECONDS));
        setField("hostnameResolver",
                (ESPHomeHostnameResolver.HostnameResolver) hostname -> InetAddress.getByName("127.0.0.1"));

        try {
            Future<InetAddress> addressFuture = resolver.resolve("device.example", 5);
            releaseExecutor.countDown();
            InetAddress address = addressFuture.get(1, TimeUnit.SECONDS);

            assertEquals("127.0.0.1", address.getHostAddress());
        } finally {
            releaseExecutor.countDown();
        }
    }

    @Test
    void resolvesLocalHostnameUsingJmDns() throws Exception {
        JmDNS jmDNS = org.mockito.Mockito.mock(JmDNS.class);
        ServiceInfo service = org.mockito.Mockito.mock(ServiceInfo.class);
        when(mdnsClient.getClientInstances()).thenReturn(Set.of(jmDNS));
        when(jmDNS.list(eq("_esphomelib._tcp.local."), anyLong())).thenReturn(new ServiceInfo[] { service });
        when(service.getServer()).thenReturn("device.local.");
        when(service.getInet4Address()).thenReturn((Inet4Address) InetAddress.getByName("127.0.0.1"));

        InetAddress address = resolver.resolve("device.local", 5).get();

        assertEquals("127.0.0.1", address.getHostAddress());
    }

    @Test
    void resolvesCachedMdnsAddressWithoutActiveLookup() throws Exception {
        ServiceInfo service = org.mockito.Mockito.mock(ServiceInfo.class);
        when(service.getServer()).thenReturn("Device.local.");
        when(service.getInet4Addresses())
                .thenReturn(new Inet4Address[] { (Inet4Address) InetAddress.getByName("192.0.2.1") });
        when(mdnsClient.list("_esphomelib._tcp.local.")).thenReturn(new ServiceInfo[] { service });
        resolver = new ESPHomeHostnameResolver(mdnsClient, executor);
        setField("hostnameResolver", (ESPHomeHostnameResolver.HostnameResolver) hostname -> {
            throw new UnknownHostException(hostname);
        });

        assertEquals("192.0.2.1", resolver.resolve("DEVICE.local.", 5).get().getHostAddress());
        org.mockito.Mockito.verify(mdnsClient, org.mockito.Mockito.never()).getClientInstances();
    }

    @Test
    void resolvesConcurrentRequestsIndependently() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        setField("hostnameResolver", (ESPHomeHostnameResolver.HostnameResolver) hostname -> {
            bothStarted.countDown();
            try {
                assertTrue(bothStarted.await(1, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new UnknownHostException(hostname);
            }
            if (hostname.equals("missing.example")) {
                throw new UnknownHostException(hostname);
            }
            return InetAddress.getByName("192.0.2.2");
        });

        Future<InetAddress> missing = resolver.resolve("missing.example", 5);
        Future<InetAddress> found = resolver.resolve("found.example", 5);

        assertEquals(UnknownHostException.class,
                assertThrows(ExecutionException.class, () -> missing.get()).getCause().getClass());
        assertEquals("192.0.2.2", found.get().getHostAddress());
    }

    private void assertResolvesBareHostnameInParallel(String configuredHostname, String bareHostname) throws Exception {
        JmDNS jmDNS = org.mockito.Mockito.mock(JmDNS.class);
        CountDownLatch mdnsLookupStarted = new CountDownLatch(1);
        AtomicBoolean mdnsLookupInterrupted = new AtomicBoolean();
        when(mdnsClient.getClientInstances()).thenReturn(Set.of(jmDNS));
        when(jmDNS.list(eq("_esphomelib._tcp.local."), anyLong())).thenAnswer(invocation -> {
            mdnsLookupStarted.countDown();
            try {
                TimeUnit.SECONDS.sleep(5);
            } catch (InterruptedException e) {
                mdnsLookupInterrupted.set(true);
                Thread.currentThread().interrupt();
            }
            return new ServiceInfo[0];
        });

        List<String> attemptedHostnames = Collections.synchronizedList(new ArrayList<>());
        setField("hostnameResolver", (ESPHomeHostnameResolver.HostnameResolver) hostname -> {
            attemptedHostnames.add(hostname);
            if (bareHostname.equals(hostname)) {
                try {
                    assertTrue(mdnsLookupStarted.await(1, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new UnknownHostException(hostname);
                }
                return InetAddress.getByName("127.0.0.1");
            }
            throw new UnknownHostException(hostname);
        });

        InetAddress address = resolver.resolve(configuredHostname, 5).get();

        assertEquals("127.0.0.1", address.getHostAddress());
        assertTrue(attemptedHostnames.contains(bareHostname));
        assertEventuallyTrue(mdnsLookupInterrupted);
    }

    private void assertEventuallyTrue(AtomicBoolean value) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (value.get()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertTrue(value.get());
    }

    private void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void setField(String name, Object value) throws Exception {
        Field field = ESPHomeHostnameResolver.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(resolver, value);
    }
}
