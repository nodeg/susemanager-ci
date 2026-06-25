// Mandatory variables for terracumber
variable "URL_PREFIX" {
  type    = string
  default = "https://ci.suse.de/view/Manager/view/Manager-Migration/job/manager-5.0-to-5.1-migration"
}

variable "CUCUMBER_COMMAND" {
  type    = string
  default = "export PRODUCT='SUSE-Manager' && run-testsuite"
}

variable "CUCUMBER_GITREPO" {
  type    = string
  default = "https://github.com/SUSE/spacewalk.git"
}

variable "CUCUMBER_BRANCH" {
  type    = string
  default = "Manager-5.1"
}

variable "CUCUMBER_RESULTS" {
  type    = string
  default = "/root/spacewalk/testsuite"
}

variable "MAIL_SUBJECT" {
  type    = string
  default = "Results MLM 5.0→5.1 Migration $status: $tests scenarios ($failures failed, $errors errors, $skipped skipped, $passed passed)"
}

variable "MAIL_TEMPLATE" {
  type    = string
  default = "../mail_templates/mail-template-jenkins.txt"
}

variable "MAIL_SUBJECT_ENV_FAIL" {
  type    = string
  default = "Results MLM 5.0→5.1 Migration: Environment setup failed"
}

variable "MAIL_TEMPLATE_ENV_FAIL" {
  type    = string
  default = "../mail_templates/mail-template-jenkins-env-fail.txt"
}

variable "MAIL_FROM" {
  type    = string
  default = "jenkins@suse.de"
}

variable "MAIL_TO" {
  type    = string
  default = "galaxy-ci@suse.de"
}

// Sumaform specific variables
variable "SCC_USER" {
  type = string
}

variable "SCC_PASSWORD" {
  type = string
}

variable "GIT_USER" {
  type    = string
  default = null
}

variable "GIT_PASSWORD" {
  type    = string
  default = null
}

variable "HOST_OS" {
  type        = string
  description = "Host OS: sles or micro"
  default     = "sles"
}

variable "PRERELEASE_REPO_URL" {
  type        = string
  description = "Optional pre-release repository URL"
  default     = ""
}

variable "REGISTRY_HOST" {
  type        = string
  description = "Optional custom container registry"
  default     = ""
}

terraform {
  required_version = ">= 1.6.0"
  required_providers {
    libvirt = {
      source  = "dmacvicar/libvirt"
      version = "0.8.3"
    }
  }
}

provider "libvirt" {
  uri = "qemu+tcp://suma-04.mgr.suse.de/system"
}

module "base" {
  source = "./modules/base"

  cc_username = var.SCC_USER
  cc_password = var.SCC_PASSWORD

  name_prefix = "mlm-migration-50-to-51-"
  use_avahi   = false
  domain      = "mgr.suse.de"
  images      = ["slemicro55o", "sles15sp6o", "opensuse156o"]

  mirror              = "minima-mirror-ci-bv.mgr.suse.de"
  use_mirror_images   = true
  testsuite           = true

  provider_settings = {
    pool        = "ssd"
    bridge      = "br1"
    additional_network = "192.168.50.0/24"
  }
}

module "server" {
  source = "./modules/mlm_migration_host"

  base_configuration = module.base.configuration

  name  = "server"
  image = var.HOST_OS == "micro" ? "slemicro55o" : "sles15sp6o"

  main_disk_size                = 200
  additional_disk_size          = 500
  second_additional_disk_size   = 200

  provider_settings = {
    mac    = "52:54:00:00:01:01"
    memory = 16384
    vcpu   = 4
  }

  prerelease_repo = var.PRERELEASE_REPO_URL
}

module "proxy" {
  source = "./modules/mlm_migration_host"

  base_configuration = module.base.configuration

  name  = "proxy"
  image = var.HOST_OS == "micro" ? "slemicro55o" : "sles15sp6o"

  main_disk_size = 100

  provider_settings = {
    mac    = "52:54:00:00:01:02"
    memory = 4096
    vcpu   = 2
  }

  prerelease_repo = var.PRERELEASE_REPO_URL
}

module "controller" {
  source = "./modules/controller"

  base_configuration = module.base.configuration

  name  = "controller"
  image = "opensuse156o"

  provider_settings = {
    mac    = "52:54:00:00:01:03"
    memory = 2048
    vcpu   = 2
  }

  // Cucumber repository configuration
  git_username = var.GIT_USER
  git_password = var.GIT_PASSWORD
  git_repo     = var.CUCUMBER_GITREPO
  branch       = var.CUCUMBER_BRANCH

  server_configuration = module.server.configuration
  proxy_configuration  = module.proxy.configuration
}

output "configuration" {
  value = {
    controller = module.controller.configuration
    server     = module.server.configuration
    proxy      = module.proxy.configuration
  }
}
