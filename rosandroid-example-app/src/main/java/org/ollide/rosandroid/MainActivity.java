/*
 * Copyright (C) 2014 Oliver Degener and 2018 Shaun Loughery.
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
import android.widget.Toast;

import org.ros.android.RosActivity;
import org.ros.node.NodeMainExecutor;


public class MainActivity extends RosActivity {

    public MainActivity() {
        super("ROS Android", "ROS Android");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        /* create the floating action button used to change the ROS master */
        final FloatingActionButton newMasterFAB = findViewById(R.id.newMasterFAB);
        newMasterFAB.setAlpha(0.5f);
        newMasterFAB.setOnClickListener(new View.OnClickListener() {
            @Override
            //TODO: handle this better
            public void onClick(View v) {
                try {
                    startMasterChooser();
                }
                catch (Exception e) {
                    Toast.makeText(getApplicationContext(), "Already connected to a master!", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    @Override
    protected void init(NodeMainExecutor nodeMainExecutor) {

        // set text view to show current master
        final TextView currentMasterTextView = findViewById(R.id.currentMasterTextView);

        // this java code allows this class to interact with the main thread when it returns
        // from master chooser, otherwise it would not have permission as master chooser starts the
        // ui thread
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {

                //create the floating action button used to change the ROS master
                final FloatingActionButton newMasterFAB = findViewById(R.id.newMasterFAB);
                newMasterFAB.setAlpha(0.5f);

                newMasterFAB.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {

                        startMasterChooser();
                    }
                });

                // start it as hidden because master chooser runs on startup
                newMasterFAB.hide();

                // create camera floating action button
                final FloatingActionButton tempCameraFAB = findViewById(R.id.tempCameraFAB);

                tempCameraFAB.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {

                        // start camera activity
                        startActivity(new Intent(MainActivity.this, CameraView.class));

                    }
                });

                //start camera button as hidden until they choose a master
                tempCameraFAB.hide();

                // if we are already connected to a master hide the button
                if (getMasterUri() != null)
                {
                    currentMasterTextView.setText(String.format("%s%s",
                            getString(R.string.getMasterUriConnected), getMasterUri()));

                    // let them have access to all modules
                    tempCameraFAB.show();

                } else {
                    currentMasterTextView.setText(R.string.getMasterUriNotConnected);

                    // if we are not connected to a master let them choose one
                    newMasterFAB.show();
                }
            });
        }
    }
}
