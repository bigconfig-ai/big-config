terraform {
  required_providers {
    oci = {
      source  = "oracle/oci"
      version = ">= 8.4.0"
    }
  }
}

provider "oci" {
  config_file_profile = "<{ oci-config-file-profile }>"
}

data "oci_core_subnet" "public_subnet" {
  subnet_id = "<{ oci-subnet-id }>"
}

data "oci_core_images" "ubuntu_24_04_arm" {
  compartment_id           = "<{ oci-compartment-id }>"
  operating_system         = "Canonical Ubuntu"
  operating_system_version = "24.04"
  shape                    = "VM.Standard.A1.Flex"
  sort_by                  = "TIMECREATED"
  sort_order               = "DESC"
}

resource "oci_core_instance" "ampere_vm" {
  availability_domain = "<{ oci-availability-domain }>"
  compartment_id      = "<{ oci-compartment-id }>"
  display_name        = "<{ oci-display-name }>"
  shape               = "<{ oci-shape }>"
  shape_config {
    ocpus         = <{ oci-ocpus }>
    memory_in_gbs = <{ oci-memory-in-gbs }>
  }
  create_vnic_details {
    subnet_id        = data.oci_core_subnet.public_subnet.id
    assign_public_ip = true
  }
  source_details {
    source_type             = "image"
    source_id               = data.oci_core_images.ubuntu_24_04_arm.images[0].id
    boot_volume_size_in_gbs = <{ oci-boot-volume-size-in-gbs }>
    boot_volume_vpus_per_gb = <{ oci-boot-volume-vpus-per-gb }>
  }
  metadata = {
    ssh_authorized_keys = file("<{ oci-ssh-authorized-keys }>")
  }
  connection {
    type = "ssh"
    user = "ubuntu"
    host = self.public_ip
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
    ip = oci_core_instance.ampere_vm.public_ip
    sudoer = "ubuntu"
    uid = "1001"
    name = "<{ package }>"
    user = "ubuntu"
  }
}
