package com.webank.wecross.stub.fabric;

import com.webank.wecross.stub.fabric2.account.FabricAccount;
import com.webank.wecross.stub.fabric2.FabricDriver;
import com.webank.wecross.stub.fabric2.FabricStubFactory;
import java.nio.charset.StandardCharsets;
import org.junit.Assert;
import org.junit.Test;

public class FabricDriverSignVerifyTest {
    private FabricDriver driver;
    private FabricAccount account;

    public FabricDriverSignVerifyTest() {
        FabricStubFactory stubFactory = new FabricStubFactory();
        driver = (FabricDriver) stubFactory.newDriver();
        account =
                (FabricAccount)
                        stubFactory.newAccount("fabric_user1", "classpath:accounts/fabric_user1/");
    }

    @Test
    public void allTest() {
        byte[] message = "verify good".getBytes(StandardCharsets.UTF_8);
        byte[] signBytes = driver.accountSign(account, message);
        boolean res = driver.accountVerify(account.getIdentity(), signBytes, message);

        Assert.assertTrue(res);
    }
}
