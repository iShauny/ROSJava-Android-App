package org.ollide.rosandroid;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import org.ros.address.InetAddressFactory;
import org.ros.android.BitmapFromCompressedImage;
import org.ros.android.RosActivity;
import org.ros.android.view.RosImageView;
import org.ros.namespace.GraphName;
import org.ros.node.AbstractNodeMain;
import org.ros.node.ConnectedNode;
import org.ros.node.NodeConfiguration;
import org.ros.node.NodeMain;
import org.ros.node.NodeMainExecutor;
import org.ros.node.topic.Publisher;

import geometry_msgs.Twist;
import io.github.controlwear.virtual.joystick.android.JoystickView;

public class CameraView extends RosActivity {

    public CameraView() {
        super("Camera View", "Camera View");
    }

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        //content will appear under system bars so it doesn't resize when system bars change visibility
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE);
        setContentView(R.layout.camera_view);


    }

    @Override
    protected void init(NodeMainExecutor nodeMainExecutor) {

        CameraViewInit(nodeMainExecutor);

        // initiate joystick node
        NodeMain JoystickNode = new JoystickNode();

        NodeConfiguration nodeConfiguration = NodeConfiguration.newPublic(
                InetAddressFactory.newNonLoopback().getHostAddress());
        nodeConfiguration.setMasterUri(getMasterUri());

        nodeMainExecutor.execute(JoystickNode, nodeConfiguration);

    }

    // this function will create the camera view
    private void CameraViewInit(NodeMainExecutor nodeMainExecutor) {
        //set the ros image view for the camera_view
        RosImageView<sensor_msgs.CompressedImage> CameraImage =  findViewById(R.id.CameraImage);

        //use the usb_cam topic from the robot
        // TODO: make this customizable on the master chooser?
        CameraImage.setTopicName("/usb_cam/image_raw/compressed");

        //compressed image being used
        CameraImage.setMessageType(sensor_msgs.CompressedImage._TYPE);
        CameraImage.setMessageToBitmapCallable(new BitmapFromCompressedImage());

        //create a note connection
        NodeConfiguration nodeConfiguration =
                NodeConfiguration.newPublic(InetAddressFactory.newNonLoopback().getHostAddress(),
                        getMasterUri());
        nodeMainExecutor.execute(CameraImage, nodeConfiguration.setNodeName("RosJava/VideoView"));
    }

    public class JoystickNode extends AbstractNodeMain implements NodeMain {

        @Override
        public GraphName getDefaultNodeName() {
            return GraphName.of("RosJava/JoystickNode");
        }

        @Override
        public void onStart(ConnectedNode connectedNode) {
            // credit to https://github.com/controlwear/virtual-joystick-android
            // required min sdk version 16 at least
            JoystickView Joystick = findViewById(R.id.CameraJoystick);

            // this will show current joystick values
            final TextView JoystickParamsTextView = findViewById(R.id.JoystickParametersTextView);

            // convert joystick movements to robot movement commands
            // by default refresh rate is 20/s (every 50 ms)
            // angle is calculated from right = 0 to counter-clockwise
            // (North is 90, west = 180, south = 270 right = 360/0

            // create a twist publisher connected to cmd_vel
            final Publisher<Twist> MovementPublisher =
                    connectedNode.newPublisher("/cmd_vel", geometry_msgs.Twist._TYPE);
            //TODO: Allow user to choose cmd_vel in master chooser

            Joystick.setOnMoveListener(new JoystickView.OnMoveListener() {
                @Override
                public void onMove(int angle, int strength) {
                    // convert angle into wheel movements and strength as a multiplier between 0 and 1

                    // this creates a new twist message!
                    geometry_msgs.Twist CameraMovement = MovementPublisher.newMessage();

                    // we will always set z to 0 as it is not needed for the jetson robots
                    // TODO: Create a slider of some sort to allow other robots to use the z axis?
                    CameraMovement.getAngular().setZ(0);

                    JoystickParamsTextView.setText(String.format("Axis: %d, Strength: %d", angle, strength));

                /* To better understand the next algorithm, here is an explanation:

                   Visualise a circle on an x and y axis with a max radius equal to when strength is
                   100%.

                   between 0 and 90, the x and y co-ordinates, given the r as strength and angle,
                   can be calculated using the fundamentals of SOH-CAH-TOA:

                   x = (str)*Sin(angle) and y = (str) * cos(angle)

                   We can use this fundamental law of trigonometry and apply it to all 4 regions of
                   our visualised axis. The trigonometry equations remain the same, but our angles
                   change as the angle is calculated between 0 and 360 from the far right
                   counter-clockwise.

                   Between 0 -> 90, angle = angle
                   Between 90 -> 180, angle = 180 - angle
                   Between 180 -> 270, angle = angle - 180
                   Between 270 -> 360, angle = 360 - angle
                */

                    int theta = 0;

                    if ((angle >= 0) && (angle <= 90))
                    {
                        theta = angle;
                    }
                    else if ((angle >= 90) && (angle <= 180))
                    {
                        theta = 180 - angle;
                    }
                    else if ((angle >= 180) && (angle <= 270))
                    {
                        theta = angle - 180;
                    }
                    else if ((angle >= 270) && (angle <= 360))
                    {
                        theta = 360 - angle;
                    }

                    // Math.round() returns a long when rounding a double so need to force int
                    int x = (int) Math.round((strength) * Math.sin(theta));
                    int y = (int) Math.round((strength) * Math.cos(theta));

                    // publish our new x and y values
                    CameraMovement.getAngular().setX(x);
                    CameraMovement.getAngular().setY(y);
                }
            });
        }
    }


    @Override
    public void onBackPressed() {
        startActivity(new Intent(CameraView.this,
                MainActivity.class));
    }
}



