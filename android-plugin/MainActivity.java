package com.uno3d.lan;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(UnoLanBridgePlugin.class);
        super.onCreate(savedInstanceState);
    }
}
