---
title: Vortex GUI
id: vortex-gui
sidebar_position: 5
---

<div>
    <img src="/img/home-screen.png" alt="home screen"/>
</div>

---

<br />

GUI for interacting with the [LnVortex client](https://github.com/ln-vortex/ln-vortex).

The Vortex GUI is the recommended way for users to interact with the client. It is fully featured and makes everything only a few clicks away.

The Vortex GUI available as an electron app or as a web service.

## Electron App

TODO when electron app is complete

## Local Development

### Configuration

Install [node version 18.x](https://nodejs.org/en/about/releases/).

Copy the `.env.sample` file to `.env.local` and update the values.

### Run

Run the app in development mode:

```
yarn dev
```

Or run an optimized production build:

```
yarn build
yarn start
```

Open [http://localhost:3000/](http://localhost:3000/) to see the app.
