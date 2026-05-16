terraform {
  backend "s3" {
    bucket = "<{ s3-bucket }>"
    key    = "<{ target-object }>.tfstate"
    region = "<{ s3-region }>"
  }
}
