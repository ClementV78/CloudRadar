region               = "us-east-1"
project              = "cloudradar"
environment          = "dev"
vpc_cidr_block       = "10.0.0.0/16"
azs                  = ["us-east-1a"]
public_subnet_cidrs  = ["10.0.1.0/24"]
private_subnet_cidrs = ["10.0.101.0/24"]

nat_instance_type    = "t3.nano"
nat_root_volume_size = 8

k3s_server_instance_type = "t3.small"
k3s_worker_instance_type = "t3.micro"
k3s_worker_min_size      = 1
k3s_worker_desired       = 1
k3s_worker_max_size      = 3
k3s_root_volume_size     = 40

edge_instance_type                 = "t3.micro"
# edge_ami_id                        = "ami-xxxxxxxxxxxxxxxxx"
edge_root_volume_size              = 40
edge_allowed_cidrs                 = ["0.0.0.0/0"]
edge_server_name                   = "_"
edge_basic_auth_user               = "cloudradar"
edge_basic_auth_ssm_parameter_name = "/cloudradar/edge/basic-auth"
edge_ssm_egress_enabled            = true
edge_dashboard_nodeport            = 30080
edge_api_nodeport                  = 30081
edge_health_nodeport               = 32736
edge_enable_http_redirect          = true
edge_ssm_vpc_endpoints_enabled     = false

# Optional: enable EC2 Serial Console login for ec2-user (temporary).
# Provide a SHA-512 password hash via TF_VAR_k3s_server_serial_console_password_hash.
k3s_server_serial_console_password_hash = ""
