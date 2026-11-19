# GeoCoin Portable Miner

Portable Windows CPU miner for GeoCoin. Keep the application, config, and logs on a USB drive and run it yourself on a computer you own or are authorized to use.

Mining is stopped until the user confirms `start`. USB insertion does not run programs automatically on Windows. This MVP does not install a background component or scheduled task.

## Quick start guide

Open the [interactive Russian quick-start guide](https://thetimkol.github.io/geocoin-portable-miner/) to prepare a GeoCoin wallet, start and sync a node, configure miner-role RPC, and run this portable miner. The [text version](docs/README.md) and the [page source](docs/index.html) are also available in this repository.

## GeoCoin-Start for Windows

The Russian desktop installer and simplified client are in [`installer/GeoCoinStart`](installer/GeoCoinStart). The one-file self-contained EXE downloads the official GeoCoin Windows package directly from the author's release, verifies its pinned SHA-256, then provides four sections: overview, CPU mining, send, and receive. It keeps wallet data in the user's Local AppData folder. See the [installer notes](installer/GeoCoinStart/README.md) for the current scope and build instructions.

## Requirements

- Java 21 or newer to run the JAR
- GeoCoin node with a dedicated miner-role JSON-RPC account
- HTTPS for remote RPC; HTTP is accepted only for loopback
- For GPU mining, a vendor driver that provides an OpenCL runtime

## Build

Requirements: JDK 21 or newer and Maven 3.9 or newer. From this folder, run `scripts\build.cmd`. The shaded JAR is written to `target/GeoCoinPortableMiner.jar`. Use `run.cmd` to launch it with Java from `JAVA_HOME` or `PATH`.

## First run

Copy `config.example.properties` to `config.properties` in the project folder and set the node URL, miner username, GeoCoin reward address, worker count, and CPU limit. On first launch, the program creates `rpc-miner.pass` in that same folder. Put the miner-role RPC password on its first line, then run `run.cmd` again. This follows GeoCoin's existing password-file convention. The file is plaintext: keep the USB physically secure and never commit or share it. Both local config files stay outside `target`, so rebuilding does not erase them.

Run `run.cmd` from this project folder:

```powershell
.\run.cmd
```

The miner reads `node.username` from `config.properties` and the password from `rpc-miner.pass`. Enter `start` to begin, `status` to view activity, and `stop` or `quit` to stop. Keep RPC credentials private; never use an admin RPC account.

## GPU mining

The miner supports OpenCL GPUs from NVIDIA, AMD, and Intel when the machine has a compatible vendor driver/runtime. Start once with `gpu.enabled=false` and enter `gpu devices` to list device IDs. Then edit `config.properties`, set `gpu.enabled=true`, and list one or more IDs in `gpu.devices`, separated by commas (for example, `gpu.devices=0,1`). Optionally set `limits.gpu-percent` from 0 to 100; the limit applies to each selected GPU. Restart the app and run `start`; CPU and selected GPUs search together, with disjoint nonce ranges. `status` reports CPU and aggregate GPU rates. GPU errors are shown while CPU mining continues. Existing configs with a single `gpu.device` remain supported. CUDA/HIP-only deployments without an OpenCL runtime are not supported by this Java build.

## Control and safety

- The miner does not start on launch without confirmation.
- It uses CPU resources and may increase power use and heat. Set a modest CPU limit first.
- Ctrl+C requests a clean stop. If the USB drive disappears, the application exits.
- No files are copied to the host. No startup task is installed.
- For a network other than the one configured on your node, use the corresponding GeoCoin node and reward address.

## License

MIT. See `LICENSE` and `NOTICE.md` for attribution to the GeoCoin Paper Miner implementation used as a reference.
