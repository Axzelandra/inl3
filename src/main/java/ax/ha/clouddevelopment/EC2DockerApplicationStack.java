package ax.ha.clouddevelopment;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.*;
import software.amazon.awscdk.services.elasticloadbalancingv2.AddApplicationTargetsProps;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationListener;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationLoadBalancer;
import software.amazon.awscdk.services.elasticloadbalancingv2.BaseApplicationListenerProps;
import software.amazon.awscdk.services.elasticloadbalancingv2.targets.InstanceIdTarget;
import software.amazon.awscdk.services.elasticloadbalancingv2.targets.InstanceTarget;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.route53.*;
import software.amazon.awscdk.services.route53.targets.LoadBalancerTarget;
import software.constructs.Construct;

import java.util.Arrays;

public class EC2DockerApplicationStack extends Stack {

    // Do not remove these variables. The hosted zone can be used later when creating DNS records
    private final IHostedZone hostedZone = HostedZone.fromHostedZoneAttributes(this, "HaHostedZone", HostedZoneAttributes.builder()
            .hostedZoneId("Z0413857YT73A0A8FRFF")
            .zoneName("cloud-ha.com")
            .build());

    // Do not remove, you can use this when defining what VPC your security group, instance and load balancer should be part of.
    private final IVpc vpc = Vpc.fromLookup(this, "MyVpc", VpcLookupOptions.builder()
            .isDefault(true)
            .build());

    public EC2DockerApplicationStack(final Construct scope, final String id, final StackProps props, final String groupName) {
        super(scope, id, props);

        // TODO: Define your cloud resources here.

        SecurityGroup securityGroup = SecurityGroup.Builder.create(this, "SecurityGroup")
                .vpc(vpc)
                .allowAllOutbound(true)
                .build();

        securityGroup.addIngressRule(Peer.anyIpv4(), Port.tcp(80), "Allow HTTP traffic");

        Role role = Role.Builder.create(this, "EC2Role")
                .assumedBy(new ServicePrincipal("ec2.amazonaws.com"))
                .managedPolicies(Arrays.asList(
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonSSMManagedInstanceCore"),
                        ManagedPolicy.fromAwsManagedPolicyName("AmazonEC2ContainerRegistryReadOnly")
                ))
                .build();

Instance instance = Instance.Builder.create(this, "Instance")
        .instanceType(InstanceType.of(InstanceClass.BURSTABLE3,InstanceSize.MICRO))
        .machineImage(MachineImage.latestAmazonLinux2())
        .vpc(vpc)
        .securityGroup(securityGroup)
        .role(role)
        .vpcSubnets(SubnetSelection.builder().subnetType(SubnetType.PUBLIC).build())
        .build();

instance.addUserData(
        "yum install docker -y",
        "sudo systemctl start docker",
        "aws ecr get-login-password --region eu-north-1 | docker login --username AWS --password-stdin 292370674225.dkr.ecr.eu-north-1.amazonaws.com",
        "docker run -d --name my-application -p 80:8080 292370674225.dkr.ecr.eu-north-1.amazonaws.com/webshop-api:latest"
);

        ApplicationLoadBalancer alb = ApplicationLoadBalancer.Builder.create(this, "ALB")
                .vpc(vpc)
                .internetFacing(true)
                .loadBalancerName("MtALB")
                .vpcSubnets(SubnetSelection.builder().subnetType(SubnetType.PUBLIC).build())
                .build();

        ApplicationListener listener = alb.addListener("Listener", BaseApplicationListenerProps.builder()
                .port(80)
                .open(true)
                .build());

        listener.addTargets("Target", AddApplicationTargetsProps.builder()
                .port(80)
                .targets(Arrays.asList(new InstanceTarget(instance)))
                .build());

        ARecord aRecord = ARecord.Builder.create(this, "AliasRecord")
                .zone(hostedZone)
                .target(RecordTarget.fromAlias(new LoadBalancerTarget(alb)))
                .recordName("api")
                .build();
    }
}