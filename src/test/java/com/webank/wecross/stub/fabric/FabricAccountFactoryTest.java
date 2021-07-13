package com.webank.wecross.stub.fabric;

import com.webank.wecross.stub.fabric2.account.FabricAccount;
import com.webank.wecross.stub.fabric2.account.FabricAccountFactory;
import com.webank.wecross.stub.fabric2.common.FabricType;
import org.junit.Assert;
import org.junit.Test;

public class FabricAccountFactoryTest {
    @Test
    public void buildTest() {
        FabricAccount fabricAccount =
                FabricAccountFactory.build("fabric2", "classpath:accounts/fabric_admin");

        Assert.assertEquals(fabricAccount.getName(), "fabric2");
        Assert.assertEquals(fabricAccount.getType(), FabricType.Account.FABRIC_ACCOUNT);
        System.out.println(fabricAccount.getIdentity());
    }
}
