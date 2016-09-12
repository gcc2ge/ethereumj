package org.ethereum.mytest;

import org.ethereum.crypto.ECKey;
import org.junit.Test;
import org.spongycastle.util.encoders.Hex;

/**
 * Created by admin on 2016/9/8.
 */
public class CreateAccount {
    @Test
    public void creeateAccountTest(){
        ECKey key = new ECKey();

        byte[] addr = key.getAddress();
        byte[] priv = key.getPrivKeyBytes();

        String addrBase16 = Hex.toHexString(addr);
        String privBase16 = Hex.toHexString(priv);

        System.out.println("Address     : " + addrBase16);
        System.out.println("Private Key : " + privBase16);
    }
}
