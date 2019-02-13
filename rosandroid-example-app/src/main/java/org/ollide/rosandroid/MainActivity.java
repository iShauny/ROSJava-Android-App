/*
 * Copyright (C) 2014 Oliver Degener.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.ollide.rosandroid;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.design.widget.FloatingActionButton;
import android.view.View;
import android.widget.TextView;

import org.ros.address.InetAddressFactory;
import org.ros.android.RosActivity;
import org.ros.node.NodeConfiguration;
import org.ros.node.NodeMain;
import org.ros.node.NodeMainExecutor;


public class MainActivity extends RosActivity {

    public MainActivity() {
        super("Ros Android", "Ros Android");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }

    @Override
    protected void init(NodeMainExecutor nodeMainExecutor) {


        //create the floating action button used to change the ROS master
        FloatingActionButton newMasterFAB = findViewById(R.id.newMasterFAB);
        newMasterFAB.setAlpha(0.5f);

        FloatingActionButton tempCameraFAB = findViewById(R.id.tempCameraFAB);
        tempCameraFAB.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, CameraView.class));

            }
        });

        //set text view to show current master
        final TextView currentMasterTextView = findViewById(R.id.currentMasterTextView);

        if (getMasterUri() != null) // if we are already connected to a master hide the button
        {
            newMasterFAB.hide();
            //this java code allows this class to interact with the main thread when it returns
            //from master chooser
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    currentMasterTextView.setText(String.format("%s%s",
                            getString(R.string.getMasterUriConnected), getMasterUri()));
                }
            });
        }
        else
        {
            newMasterFAB.show();
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    currentMasterTextView.setText(R.string.getMasterUriNotConnected);


                }
            });
        }

        NodeMain node = new SimplePublisherNode();

        NodeConfiguration nodeConfiguration = NodeConfiguration.newPublic(
                InetAddressFactory.newNonLoopback().getHostAddress());
        nodeConfiguration.setMasterUri(getMasterUri());

        nodeMainExecutor.execute(node, nodeConfiguration);
    }
}
