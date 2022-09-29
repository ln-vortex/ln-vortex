"use strict";(self.webpackChunklnvortex_com=self.webpackChunklnvortex_com||[]).push([[671],{3905:(e,t,n)=>{n.d(t,{Zo:()=>u,kt:()=>d});var r=n(7294);function o(e,t,n){return t in e?Object.defineProperty(e,t,{value:n,enumerable:!0,configurable:!0,writable:!0}):e[t]=n,e}function a(e,t){var n=Object.keys(e);if(Object.getOwnPropertySymbols){var r=Object.getOwnPropertySymbols(e);t&&(r=r.filter((function(t){return Object.getOwnPropertyDescriptor(e,t).enumerable}))),n.push.apply(n,r)}return n}function i(e){for(var t=1;t<arguments.length;t++){var n=null!=arguments[t]?arguments[t]:{};t%2?a(Object(n),!0).forEach((function(t){o(e,t,n[t])})):Object.getOwnPropertyDescriptors?Object.defineProperties(e,Object.getOwnPropertyDescriptors(n)):a(Object(n)).forEach((function(t){Object.defineProperty(e,t,Object.getOwnPropertyDescriptor(n,t))}))}return e}function l(e,t){if(null==e)return{};var n,r,o=function(e,t){if(null==e)return{};var n,r,o={},a=Object.keys(e);for(r=0;r<a.length;r++)n=a[r],t.indexOf(n)>=0||(o[n]=e[n]);return o}(e,t);if(Object.getOwnPropertySymbols){var a=Object.getOwnPropertySymbols(e);for(r=0;r<a.length;r++)n=a[r],t.indexOf(n)>=0||Object.prototype.propertyIsEnumerable.call(e,n)&&(o[n]=e[n])}return o}var s=r.createContext({}),c=function(e){var t=r.useContext(s),n=t;return e&&(n="function"==typeof e?e(t):i(i({},t),e)),n},u=function(e){var t=c(e.components);return r.createElement(s.Provider,{value:t},e.children)},p={inlineCode:"code",wrapper:function(e){var t=e.children;return r.createElement(r.Fragment,{},t)}},m=r.forwardRef((function(e,t){var n=e.components,o=e.mdxType,a=e.originalType,s=e.parentName,u=l(e,["components","mdxType","originalType","parentName"]),m=c(n),d=o,v=m["".concat(s,".").concat(d)]||m[d]||p[d]||a;return n?r.createElement(v,i(i({ref:t},u),{},{components:n})):r.createElement(v,i({ref:t},u))}));function d(e,t){var n=arguments,o=t&&t.mdxType;if("string"==typeof e||o){var a=n.length,i=new Array(a);i[0]=m;var l={};for(var s in t)hasOwnProperty.call(t,s)&&(l[s]=t[s]);l.originalType=e,l.mdxType="string"==typeof e?e:o,i[1]=l;for(var c=2;c<a;c++)i[c]=n[c];return r.createElement.apply(null,i)}return r.createElement.apply(null,n)}m.displayName="MDXCreateElement"},9881:(e,t,n)=>{n.r(t),n.d(t,{assets:()=>s,contentTitle:()=>i,default:()=>p,frontMatter:()=>a,metadata:()=>l,toc:()=>c});var r=n(7462),o=(n(7294),n(3905));const a={sidebar_position:1},i="Intro",l={unversionedId:"intro",id:"intro",title:"Intro",description:"---",source:"@site/docs/intro.md",sourceDirName:".",slug:"/intro",permalink:"/docs/intro",draft:!1,editUrl:"https://github.com/ln-vortex/lnvortex.com/docs/intro.md",tags:[],version:"current",sidebarPosition:1,frontMatter:{sidebar_position:1},sidebar:"tutorialSidebar",next:{title:"Protocol Documentation",permalink:"/docs/ProtocolDocumentation"}},s={},c=[{value:"Compatibility",id:"compatibility",level:2},{value:"Building from source",id:"building-from-source",level:2},{value:"Scala/Java",id:"scalajava",level:3},{value:"Sdkman",id:"sdkman",level:4},{value:"Coursier",id:"coursier",level:4},{value:"macOS install",id:"macos-install",level:3},{value:"Running the client",id:"running-the-client",level:3}],u={toc:c};function p(e){let{components:t,...n}=e;return(0,o.kt)("wrapper",(0,r.Z)({},u,n,{components:t,mdxType:"MDXLayout"}),(0,o.kt)("h1",{id:"intro"},"Intro"),(0,o.kt)("div",null,(0,o.kt)("img",{src:"vortex-light-mode.svg#gh-light-mode-only",alt:"ln-vortex"}),(0,o.kt)("img",{src:"vortex-dark-mode.svg#gh-dark-mode-only",alt:"ln-vortex"})),(0,o.kt)("hr",null),(0,o.kt)("p",null,(0,o.kt)("a",{parentName:"p",href:"https://github.com/ln-vortex/ln-vortex/actions"},(0,o.kt)("img",{parentName:"a",src:"https://github.com/ln-vortex/ln-vortex/workflows/CI%20to%20Docker%20Hub/badge.svg",alt:"Build Status"})),"\n",(0,o.kt)("a",{parentName:"p",href:"https://coveralls.io/github/ln-vortex/ln-vortex?branch=master"},(0,o.kt)("img",{parentName:"a",src:"https://coveralls.io/repos/github/ln-vortex/ln-vortex/badge.svg?branch=master",alt:"Coverage Status"})),"\n",(0,o.kt)("a",{parentName:"p",href:"https://opensource.org/licenses/MIT"},(0,o.kt)("img",{parentName:"a",src:"https://img.shields.io/badge/License-MIT-yellow.svg",alt:"License: MIT"}))),(0,o.kt)("p",null,"Vortex is a tool to allow users to open lightning channels in a collaborative transaction when\nusing ",(0,o.kt)("a",{parentName:"p",href:"https://github.com/lightningnetwork/lnd"},"lnd")," and ",(0,o.kt)("a",{parentName:"p",href:"https://github.com/ElementsProject/lightning"},"Core Lightning")),(0,o.kt)("h2",{id:"compatibility"},"Compatibility"),(0,o.kt)("p",null,"Vortex is compatible with ",(0,o.kt)("inlineCode",{parentName:"p"},"lnd")," version v0.15.1-beta and core lightning version v0.10.2."),(0,o.kt)("h2",{id:"building-from-source"},"Building from source"),(0,o.kt)("h3",{id:"scalajava"},"Scala/Java"),(0,o.kt)("p",null,"You can choose to install the Scala toolchain with sdkman or coursier."),(0,o.kt)("h4",{id:"sdkman"},"Sdkman"),(0,o.kt)("p",null,"You can install sdkman ",(0,o.kt)("a",{parentName:"p",href:"https://sdkman.io/install"},"here")),(0,o.kt)("p",null,"Next you can install ",(0,o.kt)("inlineCode",{parentName:"p"},"java")," and ",(0,o.kt)("inlineCode",{parentName:"p"},"sbt")," with"),(0,o.kt)("pre",null,(0,o.kt)("code",{parentName:"pre"},"sdk install java # not always needed\nsdk install sbt\n")),(0,o.kt)("h4",{id:"coursier"},"Coursier"),(0,o.kt)("p",null,"If you don't like ",(0,o.kt)("inlineCode",{parentName:"p"},"curl"),", you can use OS specific package managers to install coursier ",(0,o.kt)("a",{parentName:"p",href:"https://get-coursier.io/docs/2.0.0-RC2/cli-overview.html#installation"},"here")),(0,o.kt)("blockquote",null,(0,o.kt)("p",{parentName:"blockquote"},"ln-vortex requires java9+ for development environments. If you do not have java9+ installed, you will not be able to build ln-vortex.\n",(0,o.kt)("a",{parentName:"p",href:"https://github.com/bitcoin-s/bitcoin-s/issues/3298"},"You will run into this error if you are on java8 or lower"))),(0,o.kt)("p",null,"If you follow the coursier route, ",(0,o.kt)("a",{parentName:"p",href:"https://get-coursier.io/docs/2.0.0-RC6-15/cli-java.html"},"you can switch to a java11 version by running")),(0,o.kt)("pre",null,(0,o.kt)("code",{parentName:"pre"},"cs java --jvm adopt:11 --setup\n")),(0,o.kt)("h3",{id:"macos-install"},"macOS install"),(0,o.kt)("pre",null,(0,o.kt)("code",{parentName:"pre"},"brew install scala\nbrew install sbt\n")),(0,o.kt)("h3",{id:"running-the-client"},"Running the client"),(0,o.kt)("p",null,"Running the client can simply be done by running"),(0,o.kt)("pre",null,(0,o.kt)("code",{parentName:"pre"},"sbt rpcServer/run\n")))}p.isMDXComponent=!0}}]);