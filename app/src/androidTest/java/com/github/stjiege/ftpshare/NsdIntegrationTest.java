package com.github.stjiege.ftpshare;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.github.stjiege.ftpshare.utils.FtpDiscoveryManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class NsdIntegrationTest {
    @Test public void discoverResolveAndWithdrawAfterRapidToggle() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        NsdManager nsd=(NsdManager)context.getSystemService(Context.NSD_SERVICE);
        FtpDiscoveryManager publisher=new FtpDiscoveryManager(context);
        String name="FTPS-test-"+System.nanoTime();
        CountDownLatch found=new CountDownLatch(1),lost=new CountDownLatch(1),resolved=new CountDownLatch(1);
        AtomicReference<NsdServiceInfo> service=new AtomicReference<>();
        NsdManager.DiscoveryListener discovery=new NsdManager.DiscoveryListener(){
            public void onDiscoveryStarted(String type){}
            public void onDiscoveryStopped(String type){}
            public void onStartDiscoveryFailed(String type,int error){}
            public void onStopDiscoveryFailed(String type,int error){}
            public void onServiceFound(NsdServiceInfo info){if(info.getServiceName().startsWith(name)){service.set(info);found.countDown();}}
            public void onServiceLost(NsdServiceInfo info){if(info.getServiceName().startsWith(name))lost.countDown();}
        };
        try {
            nsd.discoverServices("_ftp._tcp.",NsdManager.PROTOCOL_DNS_SD,discovery);
            publisher.register(45678,"cancel-"+name,false);publisher.unregister();publisher.register(45679,name,true);
            assertTrue("NSD publication not discovered",found.await(25,TimeUnit.SECONDS));
            // Let the first canceled registration retire before resolving the desired one.
            Thread.sleep(1500);
            nsd.resolveService(service.get(),new NsdManager.ResolveListener(){
                public void onResolveFailed(NsdServiceInfo info,int error){resolved.countDown();}
                public void onServiceResolved(NsdServiceInfo info){service.set(info);resolved.countDown();}
            });
            assertTrue(resolved.await(15,TimeUnit.SECONDS));
            assertEquals(45679,service.get().getPort());
            assertEquals("required",new String(service.get().getAttributes().get("tls"),"UTF-8"));
            publisher.unregister();
            assertTrue("NSD publication not withdrawn",lost.await(15,TimeUnit.SECONDS));
        } finally {publisher.unregister();nsd.stopServiceDiscovery(discovery);}
    }
}
