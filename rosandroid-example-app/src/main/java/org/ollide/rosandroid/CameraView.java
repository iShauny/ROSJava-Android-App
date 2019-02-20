package org.ollide.rosandroid;

import android.content.Intent;
import android.os.Build;
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

import java.util.Locale;

import geometry_msgs.Twist;
import io.github.controlwear.virtual.joystick.android.JoystickView;

public class CameraView extends RosActivity {

    public CameraView() {
        super("Camera View", "Camera View");
    }

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        /*
        content will appear under system bars so it doesn't resize when system bars change
        visibility. This is only supported kitkat and above, so run fullscreen if below
        this version
        */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE);
        }
        else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
        setContentView(R.layout.camera_view);
    }

    @Override
    protected void init(NodeMainExecutor nodeMainExecutor) {
        /* TODO: Add a screenshot button */

        CameraViewInit(nodeMainExecutor);
        JoystickNodeInit(nodeMainExecutor);
    }

    private void JoystickNodeInit(NodeMainExecutor  nodeMainExecutor) {
        /* initiate joystick node */
        NodeMain JoystickNode = new JoystickNode();

        NodeConfiguration nodeConfiguration = NodeConfiguration.newPublic(
                InetAddressFactory.newNonLoopback().getHostAddress());
        nodeConfiguration.setMasterUri(getMasterUri());

        nodeMainExecutor.execute(JoystickNode, nodeConfiguration);
    }

    /* this function will create the camera view */
    private void CameraViewInit(NodeMainExecutor nodeMainExecutor) {
        /* set the ros image view for the camera_view */
        RosImageView<sensor_msgs.CompressedImage> CameraImage =  findViewById(R.id.CameraImage);

        /* use the usb_cam topic from the robot */
        /* TODO: make this customizable on the master chooser */
        CameraImage.setTopicName(getString(R.string.CameraCompressedTopic));

        /* compressed image being used */
        CameraImage.setMessageType(sensor_msgs.CompressedImage._TYPE);
        CameraImage.setMessageToBitmapCallable(new BitmapFromCompressedImage());

        /* create a note connection */
        NodeConfiguration nodeConfiguration =
                NodeConfiguration.newPublic(InetAddressFactory.newNonLoopback().getHostAddress(),
                        getMasterUri());
        nodeMainExecutor.execute(CameraImage, nodeConfiguration.setNodeName("RosJava/VideoView"));
    }

    /* this function creates the node for the joystick */
    private class JoystickNode extends AbstractNodeMain implements NodeMain {

        @Override
        public GraphName getDefaultNodeName() {
            return GraphName.of("RosJava/JoystickNode");
        }

        @Override
        public void onStart(ConnectedNode connectedNode) {
            /*
            credit to https://github.com/controlwear/virtual-joystick-android
            (requires min sdk version 16 at least)
            */
            JoystickView Joystick = findViewById(R.id.CameraJoystick);

            /* this will show current speed */
            final TextView JoystickParamsTextView = findViewById(R.id.JoystickParametersTextView);

            /*
            convert joystick movements to robot movement commands
            by default refresh rate is 20/s (every 50 ms)
            angle is calculated from right = 0 to counter-clockwise
            (North is 90, west = 180, south = 270 right = 360/0
            create a twist publisher connected to cmd_vel
            */

            final Publisher<Twist> MovementPublisher =
                    connectedNode.newPublisher(getString(R.string.CmdVelTopic),
                            geometry_msgs.Twist._TYPE);

            /* TODO: Allow user to choose cmd_vel in master chooser */

            /* used to publish our values */
            final double[] x = new double[1];
            final double[] z = new double[1];

            /* assign a MAX_SPEED for the robot here */
            /* TODO: Make this customizable in master chooser maybe? */
            final double MAX_SPEED = 0.6;

            Joystick.setOnMoveListener(new JoystickView.OnMoveListener() {
                @Override
                public void onMove(int angle, int strength) {

                    /* This line creates a new twist message */
                    geometry_msgs.Twist CameraMovement = MovementPublisher.newMessage();

                    /*
                    To better understand the next algorithm, here is an explanation:

                    Visualise a circle (representing the joystick) on a vertical x and horizontal
                    z axis with a max radius equal to when the strength is 100%.

                    Between 0 and pi/2, the x and z co-ordinates, given the r as strength and
                    angle, can be calculated using the fundamentals of SOH-CAH-TOA:

                                                     ^ x (pi/2 rad)
                                                     |
                                                     |      /|
                                                     | str / |
                                                     |    /  |
                                                     |   /   | x[0]
                                                     |  /    |
                                                     | /     |
                             ------------------------|/------|------------------> z
                           (pi rad)                  |    z[0]            (0 & 2*pi rad)
                                                     |
                                                     |
                                                     |
                                                     |
                                                     |
                                                     | (3*pi / 2 rad)

                    x = (str) * sin(angle) * MAX_SPEED and z = (str) * cos(angle) * MAX_SPEED

                    We can use this fundamental law of trigonometry and apply it to all 4
                    regions of our visualised axis. The trigonometry equations remain the same,
                    but our angles change as the angle is calculated between 0 and 2pi from the
                    far right counter-clockwise. The co-sine value is the opposite polarity of our
                    intended z velocity for moving the robot forward so it's multiplied by -1.
                    The MAX_SPEED variable represents a predefined limit for how fast the robot
                    can go.
                    */
                    /* TODO: Improve this algorithm, it is a bit messy! */


                    x[0] = (((double) strength / 100.0) *
                            Math.sin(Math.toRadians(angle)) * MAX_SPEED);

                    if ((angle >= 0) && (angle <= 180))
                        z[0] = (((double) strength / 100.0) *
                                -1.0 * Math.cos(Math.toRadians(angle)) * MAX_SPEED);
                    if ((angle >= 180) && (angle <= 360))
                        z[0] = (((double) strength / 100.0) *
                                Math.cos(Math.toRadians(angle)) * MAX_SPEED);

                    /* publish our new x and y values */
                    CameraMovement.getLinear().setX(x[0]);
                    CameraMovement.getAngular().setZ(z[0]);

                    /* let user see their speed */
                    JoystickParamsTextView.setText(String.format(Locale.ENGLISH,
                            "Linear Speed: %2.2f m/s, Angular Speed: %2.2f m/s",
                            x[0], z[0]));

                    MovementPublisher.publish(CameraMovement);
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



