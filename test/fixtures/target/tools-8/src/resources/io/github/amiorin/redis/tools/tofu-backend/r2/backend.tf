terraform {
  backend "s3" {
    bucket     = "<{ r2-bucket }>"
    key        = "<{ target-object }>.tfstate"
    region     = "auto"
    access_key = "<{ r2-access-key-id }>"
    secret_key = "<{ r2-secret-access-key }>"
    endpoints = {
      s3 = "<{ r2-endpoint }>"
    }
    skip_credentials_validation = true
    skip_metadata_api_check     = true
    skip_region_validation      = true
    skip_requesting_account_id  = true
    use_path_style              = false
  }
}
