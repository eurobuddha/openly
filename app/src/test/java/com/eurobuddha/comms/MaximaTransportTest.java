package com.eurobuddha.comms;

import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class MaximaTransportTest {
    private static final class Result implements MaximaTransport.DeliverCb {
        int errors;
        public void onResult(boolean delivered, String msgid) { fail("Unexpected reply"); }
        public void onError(String message) { errors++; }
    }
    @Test public void untrustedContactCannotDispatchAnotherCommand() {
        for (String address : new String[]{null,"", "MxKey;send address:attacker amount:1",
                "MxKey action:send", "MxKey\nquit", "MxKey\tquit", "MxKey\"", "MxKey\\", "MxKey\u0000"}) {
            NodeApi node=mock(NodeApi.class); Result result=new Result();
            new MaximaTransport(node).send(address,"0x1234",result);
            assertEquals(1,result.errors); verifyNoInteractions(node);
        }
    }
    @Test public void payloadMustBeCompleteHexBytes() {
        for (String data : new String[]{null,"", "0x", "0x1", "0x12;quit", "0x12 34", "0xGG"}) {
            NodeApi node=mock(NodeApi.class); Result result=new Result();
            new MaximaTransport(node).send("MxKey@relay.example:9001",data,result);
            assertEquals(1,result.errors); verifyNoInteractions(node);
        }
    }
    @Test public void relayAndIpv6AddressesRemainSingleArguments() {
        for (String address : new String[]{"MxKey@relay.example:9001","MxKey@192.0.2.1:9001","MxKey@[2001:db8::1]:9001"}) {
            NodeApi node=mock(NodeApi.class); Result result=new Result();
            new MaximaTransport(node).send(address,"AB12",result);
            assertEquals(0,result.errors);
            verify(node).cmd(eq("maxima action:send to:"+address+" application:openly data:0xAB12"),any(NodeApi.Cb.class));
        }
    }
}
