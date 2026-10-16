<picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://panel-assistant.io/brand/wordmark-on-dark.svg">
  <img src="https://panel-assistant.io/brand/wordmark-on-light.svg" width="360" alt="Panel Assistant">
</picture>

[![CI](https://github.com/panel-assistant/android/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/panel-assistant/android/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/panel-assistant/android?include_prereleases&sort=semver&style=flat-square&color=blue)](https://github.com/panel-assistant/android/releases)

**The Home Assistant dashboard app for Android wall panels.**

ha-paneld is the Android app installed and updated by Panel Assistant, the Home Assistant integration. Its built-in dashboard renderer filters entity updates, and hardware profiles provide controls for supported panels.

For installation, user guides, hardware support and community information, visit [panel-assistant.io](https://panel-assistant.io/).

## Development

This repository contains the Android app, its root helper and developer tooling. Start with [CONTRIBUTING.md](.github/CONTRIBUTING.md).

- [Building from source](https://panel-assistant.io/go/docs?page=building) and [local development](https://panel-assistant.io/go/docs?page=local-builds).
- [Control and Home Assistant reference](https://panel-assistant.io/go/docs?page=api): the panel's entities, its pages and endpoints, and the deprecated REST API.
- [Panel-profile development](https://panel-assistant.io/go/docs?page=profiles/readme), with [example](docs/profiles/examples/minimal-community.yaml) and [community profiles](docs/profiles/unofficial/).
- [Browser test harness](tools/test/README.md).
- [Root-helper protocol and development](helper/README.md).
- [Release process](docs/RELEASING.md) and [changelog](docs/CHANGELOG.md).
- [Security policy](.github/SECURITY.md).

## Licence

Apache-2.0. See [LICENSE](LICENSE).
