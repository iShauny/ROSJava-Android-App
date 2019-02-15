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
        CameraImage.setTopicName("/usb_cam/image_raw/compressed");

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

            /* this will show current joystick values */
            final TextView JoystickParamsTextView = findViewById(R.id.JoystickParametersTextView);

            /*
            convert joystick movements to robot movement commands
            by default refresh rate is 20/s (every 50 ms)
            angle is calculated from right = 0 to counter-clockwise
            (North is 90, west = 180, south = 270 right = 360/0
            create a twist publisher connected to cmd_vel
            */

            final Publisher<Twist> MovementPublisher =
                    connectedNode.newPublisher("/cmd_vel", geometry_msgs.Twist._TYPE);

            /* TODO: Allow user to choose cmd_vel in master chooser */

            /* not strictly needed, but allows us to store our new angles separately */
            final int[] theta = {0};
            /* used to publish our values */
            final double[] x = new double[1];
            final double[] z = new double[1];

            /* this multiplier will be used to determine which region of the four regions
               of the axis we are currently in based on the angle */
            final double[] x_multiplier = {0.0};
            final double[] z_multiplier = {0.0};

            /* assign a MAX_SPEED for the robot here */
            /* TODO: Make this customizable in master chooser maybe? */
            final double MAX_SPEED = 1.0;

            Joystick.setOnMoveListener(new JoystickView.OnMoveListener() {
                @Override
                public void onMove(int angle, int strength) {

                    /* This lin creates a new twist message */
                    geometry_msgs.Twist CameraMovement = MovementPublisher.newMessage();

                    /* we will always set z to 0 as it is not needed for the robots */
                    /* TODO: Create a view to allow other robots to use other axis? */
                    CameraMovement.getAngular().setZ(0);

                    /*
                    To better understand the next algorithm, here is an explanation:

                    Visualise a circle on a vertical x and horizontal z axis with a max radius
                    equal to when the strength is 100%.

                    Between 0 and 90, the x and z co-ordinates, given the r as strength and
                    angle, can be calculated using the fundamentals of SOH-CAH-TOA:

                                                     ^ x (pi/2 rad)
                                                     |
                                                     |      /|
                                                     | str / |
                                                     |    /  |
                                                     |   /   | x
                                                     |  /    |
                                                     | /     |
                             ------------------------|/------|------------------> z
                           (pi rad)                  |    y               (0 & 2*pi rad)
                                                     |
                                                     |
                                                     |
                                                     |
                                                     |
                                                     | (3*pi / 2 rad)

                    x = (str) * sin(angle) * MAX_SPEED and z = (str) * cos(angle) * MAX_SPEED

                    We can use this fundamental law of trigonometry and apply it to all 4
                    regions of our visualised axis. The trigonometry equations remain the same,
                    but our angles change as the angle is calculated between 0 and 360 from the
                    far right counter-clockwise. Therefore, we have to subtract an offset
                    representative of the angle. Additionally, we use multipliers to apply
                    negatives to the x and z values when they are in their respective negative
                    axis. The MAX_SPEED variable represents a predefined limit for how fast the
                    robot can go.

                    TO SUMMARISE:
                    Between 0 -> 90, angle = angle, x positive, z negative
                    Between 90 -> 180, angle = 180 - angle, x positive, z negative
                    Between 180 -> 270, angle = angle - 180, x negative, z negative
                    Between 270 -> 360, angle = 360 - angle, x negative, z positive
                    */
                    /* TODO: Improve this algorithm, it is a bit messy! */

                    /* top right quadrant */
                    if ((angle >= 0) && (angle <= 90))
                    {
                        theta[0] = angle;
                        x_multiplier[0] = 1.0;
                        z_multiplier[0] = -1.0;
                    }
                    /* top left quadrant */
                    else if ((angle >= 90) && (angle <= 180))
                    {
                        theta[0] = 180 - angle; // angle offset
                        x_multiplier[0] = 1.0;
                        z_multiplier[0] = 1.0;
                    }
                    /* bottom left quadrant */
                    else if ((angle >= 180) && (angle <= 270))
                    {
                        theta[0] = angle - 180; // angle offset
                        x_multiplier[0] = -1.0;
                        z_multiplier[0] = -1.0;
                    }
                    /* bottom right quadrant */
                    else if ((angle >= 270) && (angle <= 360))
                    {
                        theta[0] = 360 - angle; // angle offset
                        x_multiplier[0] = -1.0;
                        z_multiplier[0] = 1.0;
                    }

                    x[0] = (((double) strength / 100.0) * x_multiplier[0] *
                            Math.sin(Math.toRadians(theta[0])) * MAX_SPEED);

                    z[0] = (((double) strength / 100.0) * z_multiplier[0] *
                            Math.cos(Math.toRadians(theta[0])) * MAX_SPEED);

                    /* publish our new x and y values */
                    CameraMovement.getLinear().setX(x[0]);
                    CameraMovement.getAngular().setZ(z[0]);

                    /* let user see their speed */
                    JoystickParamsTextView.setText(String.format(Locale.ENGLISH,
                            "Linear Speed: %2.2f, Angular Speed: %2.2f",
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



