## Local Setup Instructions

If you want to run the bootcamp locally, follow the instructions below for your platform.

Note: Java 11 or later is recommended. If you have multiple Java versions installed, select the appropriate version before running `jupyter notebook`:

* On Windows: https://gist.github.com/rwunsch/d157d5fe09e9f7cdc858cec58c8462d6
* On macOS: https://stackoverflow.com/questions/21964709/how-to-set-or-change-the-default-java-jdk-version-on-os-x

### Local Installation using Docker - Linux/macOS/Windows

Make sure you have Docker [installed](https://docs.docker.com/get-docker/) on your system, or alternatively [nerdctl](https://github.com/containerd/nerdctl) for a Docker-compatible CLI.

#### Prebuilt Multi-Architecture Image (Recommended)

The `sysprog21/chisel-bootcamp` image supports both x86-64 and Arm64 architectures. It bundles:
- Ubuntu 24.04
- OpenJDK 8
- Scala 2.12.10 with Almond 0.9.1 kernel
- Jupyter Lab
- Graphviz for circuit visualization

Run the container:

Using Docker:
```bash
docker run -it --rm -p 8888:8888 sysprog21/chisel-bootcamp
```

Using nerdctl:
```bash
nerdctl run -it --rm -p 8888:8888 sysprog21/chisel-bootcamp
```

The container starts Jupyter Lab (not classic Jupyter Notebook). Look for output like:
```
    To access the server, open this file in a browser:
        file:///home/bootcamp/.local/share/jupyter/runtime/jpserver-7-open.html
    Or copy and paste one of these URLs:
        http://d77238b933b7:8888/lab?token=TOKEN_HERE
        http://127.0.0.1:8888/lab?token=TOKEN_HERE
```

Copy the last URL (starting with `http://127.0.0.1:8888/lab`) into your browser to access the bootcamp.

### Local Installation - macOS/Linux

This bootcamp uses Jupyter notebooks.
Jupyter notebooks allow you to interactively run code in your browser.
It supports multiple programming languages.
For this bootcamp, we'll install jupyter first and then the Scala-specific jupyter backend (now called almond).


#### Jupyter
First install Jupyter.

Dependencies: openssh-client, openjdk-11-jre, openjdk-11-jdk (-headless OK for both), ca-certificates-java

Install Jupyter using pip3 (see [Jupyter installation docs](https://jupyter.org/install)):
```
pip3 install --upgrade pip
pip3 install jupyter --ignore-installed
```

If pip3 isn't working out of the box (possibly because your Python3 version is out of date), you can try `python3 -m pip` in lieu of `pip3`.

(To reinstall jupyter later for whatever reason, you can use `--no-deps` to avoid re-installing all the dependencies.)

You may want to try out Jupyter lab, the newer interface developed by Project Jupyter.
It is especially useful if you want to be able to run a terminal emulator in your browser.
It can be installed with `pip3`:
```
pip3 install jupyterlab
```

#### Jupyter Backend for Scala

If you experience errors or issues with this section, try running `rm -rf ~/.local/share/jupyter/kernels/scala/` first.

Download coursier and use it to install Almond (see [Almond installation docs](https://almond.sh/docs/quick-start-install) for details):
```bash
curl -L -o coursier https://github.com/coursier/coursier/releases/latest/download/coursier
chmod +x coursier
./coursier launch --use-bootstrap almond -- --install
```

This installs Almond with compatible Scala 2.12 versions. You can delete the `coursier` file afterward if desired.

#### Visualizations

[Graphviz](https://graphviz.org/download/) is required to show visualizations of Chisel modules, such as in the demo page. However, visualizations are optional as the other Chisel and Scala features will work without it.

#### Install bootcamp
Now clone the bootcamp repo and install the customization script.
If you already have one, append this script to it.

```
git clone https://github.com/freechipsproject/chisel-bootcamp.git
cd chisel-bootcamp
mkdir -p ~/.jupyter/custom
cp source/custom.js ~/.jupyter/custom/custom.js
```

And to start the bootcamp on your local machine:
```
jupyter notebook
```

If you installed Jupyter Lab, run `jupyter-lab` instead.


### Local Installation - Windows

These instructions cover Windows 10 and later. Running the command prompt in Administrator Mode is recommended for installation steps.

#### Install Java

Ensure Java 17 LTS or later is installed. Test by typing `java -version` in a command prompt. If not found, install from [Adoptium](https://adoptium.net/temurin/releases/).

#### Install Jupyter
Jupyter recommends using the Anaconda distribution, here is the
[Windows download](https://www.anaconda.com/download/#windows).

Near the end of the Jupyter installation is a question about whether to add Jupyter to the PATH.
Windows does not recommend this, but I do.  It will make it easier to run using the command prompt.

If you did not elect to add Jupyter to the PATH, start a prompt using the
"Anaconda Prompt (Anaconda3)" shortcut from the Start Menu.

#### Install Scala components

Download the latest Coursier from [GitHub releases](https://github.com/coursier/coursier/releases/latest/download/coursier).

Navigate to your download folder and run:
```bash
java -jar coursier launch almond -- --install
```

#### Visualizations

[Graphviz](https://graphviz.org/download/) is required to show visualizations of Chisel modules, such as in the demo page. However, visualizations are optional as the other Chisel and Scala features will work without it.

#### Install the chisel-bootcamp repo.
Download the [chisel-bootcamp](https://github.com/freechipsproject/chisel-bootcamp) as a zip file (or use a Windows git client)
and unpack it in a directory you have access to.
Ideally, you should put it in a path that has no spaces.

Install the customization script by moving `chisel-bootcamp/source/custom.js` to 
`%HOMEDRIVE%%HOMEPATH%\.jupyter\custom\custom.js`.
If you already have a custom.js file, append this script to it.

#### Launch the Jupyter and the bootcamp
In the directory containing the unpacked chisel-bootcamp repo, from a new command window type:
```bash
jupyter notebook
```
This should start the bootcamp server and open a top page bootcamp menu in your default browser.  If it does not
look for the something like the following in the command window and copy and paste the link you see into
a browser window.
```bash
    Copy/paste this URL into your browser when you connect for the first time,
    to login with a token:
        http://localhost:8888/?token=9c503729c379fcb3c7a17087f05462c733c1733eb8b31d07
```

##### Proxy usage
If you require a proxy, try uncommenting and changing the relevant lines at the start of `source/load-ivy.sc`.

Good Luck!


