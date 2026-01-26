region               = "us-east-1"
project              = "cloudradar"
environment          = "prod"
vpc_cidr_block       = "10.1.0.0/16"
azs                  = ["us-east-1a"]
public_subnet_cidrs  = ["10.1.1.0/24"]
private_subnet_cidrs = ["10.1.101.0/24"]

# Optional: only needed when a NAT instance is created.
# private_route_nat_instance_id = "i-0123456789abcdef0"
