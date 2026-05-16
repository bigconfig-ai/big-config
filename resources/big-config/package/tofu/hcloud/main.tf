# Tell terraform to use the provider and select a version.
terraform {
  required_providers {
    hcloud = {
      source  = "hetznercloud/hcloud"
      version = "~> 1.45"
    }
  }
}

provider "hcloud" {
  token = "<{ hcloud-token }>"
}

resource "hcloud_server" "node1" {
  name        = "<{ hcloud-name }>"
  image       = "<{ hcloud-image }>"
  server_type = "<{ hcloud-server-type }>"
  location    = "<{ hcloud-location  }>"
  ssh_keys    = ["<{ hcloud-ssh-keys }>"]
  public_net {
    ipv4_enabled = true
    ipv6_enabled = false
  }
  # Wait for ssh before starting Ansible
  connection {
    type = "ssh"
    user = "root"
    host = self.ipv4_address
  }
  provisioner "remote-exec" {
    inline = ["ls"]
  }
  lifecycle {
    prevent_destroy = <{ compute-prevent-destroy }>
  }
}

output "params" {
  value = {
    ip = hcloud_server.node1.ipv4_address
    sudoer = "root"
    name = "<{ package }>"
    user = "root"
  }
}
