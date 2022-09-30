"use strict";(self.webpackChunkln_vortex=self.webpackChunkln_vortex||[]).push([[301],{3905:(e,t,n)=>{n.d(t,{Zo:()=>h,kt:()=>d});var i=n(7294);function o(e,t,n){return t in e?Object.defineProperty(e,t,{value:n,enumerable:!0,configurable:!0,writable:!0}):e[t]=n,e}function r(e,t){var n=Object.keys(e);if(Object.getOwnPropertySymbols){var i=Object.getOwnPropertySymbols(e);t&&(i=i.filter((function(t){return Object.getOwnPropertyDescriptor(e,t).enumerable}))),n.push.apply(n,i)}return n}function a(e){for(var t=1;t<arguments.length;t++){var n=null!=arguments[t]?arguments[t]:{};t%2?r(Object(n),!0).forEach((function(t){o(e,t,n[t])})):Object.getOwnPropertyDescriptors?Object.defineProperties(e,Object.getOwnPropertyDescriptors(n)):r(Object(n)).forEach((function(t){Object.defineProperty(e,t,Object.getOwnPropertyDescriptor(n,t))}))}return e}function s(e,t){if(null==e)return{};var n,i,o=function(e,t){if(null==e)return{};var n,i,o={},r=Object.keys(e);for(i=0;i<r.length;i++)n=r[i],t.indexOf(n)>=0||(o[n]=e[n]);return o}(e,t);if(Object.getOwnPropertySymbols){var r=Object.getOwnPropertySymbols(e);for(i=0;i<r.length;i++)n=r[i],t.indexOf(n)>=0||Object.prototype.propertyIsEnumerable.call(e,n)&&(o[n]=e[n])}return o}var l=i.createContext({}),u=function(e){var t=i.useContext(l),n=t;return e&&(n="function"==typeof e?e(t):a(a({},t),e)),n},h=function(e){var t=u(e.components);return i.createElement(l.Provider,{value:t},e.children)},c={inlineCode:"code",wrapper:function(e){var t=e.children;return i.createElement(i.Fragment,{},t)}},p=i.forwardRef((function(e,t){var n=e.components,o=e.mdxType,r=e.originalType,l=e.parentName,h=s(e,["components","mdxType","originalType","parentName"]),p=u(n),d=o,g=p["".concat(l,".").concat(d)]||p[d]||c[d]||r;return n?i.createElement(g,a(a({ref:t},h),{},{components:n})):i.createElement(g,a({ref:t},h))}));function d(e,t){var n=arguments,o=t&&t.mdxType;if("string"==typeof e||o){var r=n.length,a=new Array(r);a[0]=p;var s={};for(var l in t)hasOwnProperty.call(t,l)&&(s[l]=t[l]);s.originalType=e,s.mdxType="string"==typeof e?e:o,a[1]=s;for(var u=2;u<r;u++)a[u]=n[u];return i.createElement.apply(null,a)}return i.createElement.apply(null,n)}p.displayName="MDXCreateElement"},864:(e,t,n)=>{n.r(t),n.d(t,{assets:()=>l,contentTitle:()=>a,default:()=>c,frontMatter:()=>r,metadata:()=>s,toc:()=>u});var i=n(7462),o=(n(7294),n(3905));const r={sidebar_position:2},a="Protocol Documentation",s={unversionedId:"ProtocolDocumentation",id:"ProtocolDocumentation",title:"Protocol Documentation",description:"This is an adaptation of ZeroLink made to be usable for",source:"@site/docs/ProtocolDocumentation.md",sourceDirName:".",slug:"/ProtocolDocumentation",permalink:"/docs/ProtocolDocumentation",draft:!1,editUrl:"https://github.com/ln-vortex/ln-vortex/docs/ProtocolDocumentation.md",tags:[],version:"current",sidebarPosition:2,frontMatter:{sidebar_position:2},sidebar:"tutorialSidebar",previous:{title:"Intro",permalink:"/docs/intro"},next:{title:"RPC Docs",permalink:"/docs/rpc-docs"}},l={},u=[{value:"Considerations",id:"considerations",level:2},{value:"Protocol",id:"protocol",level:2},{value:"1. Pending &amp; Queueing Phase",id:"1-pending--queueing-phase",level:4},{value:"2. Input Registration",id:"2-input-registration",level:4},{value:"3. Output Registration",id:"3-output-registration",level:4},{value:"4. Signing Phase",id:"4-signing-phase",level:4},{value:"Privacy Considerations",id:"privacy-considerations",level:2},{value:"Dealing with change",id:"dealing-with-change",level:3},{value:"Mixing change outputs in the CoinJoin",id:"mixing-change-outputs-in-the-coinjoin",level:4},{value:"Allowing only mixed inputs",id:"allowing-only-mixed-inputs",level:4}],h={toc:u};function c(e){let{components:t,...r}=e;return(0,o.kt)("wrapper",(0,i.Z)({},h,r,{components:t,mdxType:"MDXLayout"}),(0,o.kt)("h1",{id:"protocol-documentation"},"Protocol Documentation"),(0,o.kt)("p",null,"This is an adaptation of ",(0,o.kt)("a",{parentName:"p",href:"https://github.com/nopara73/ZeroLink/blob/master/README.md"},"ZeroLink")," made to be usable for\nopening lightning channels."),(0,o.kt)("h2",{id:"considerations"},"Considerations"),(0,o.kt)("p",null,"We could not directly use the ZeroLink framework for opening lightning channels because when opening a lightning channel\nwe have only 10 minutes to broadcast the funding transaction from when we negotiate the channel with our channel peer.\nUnless we have coinjoin rounds every 10 minutes this is not possible under the initial ZeroLink spec because the blinded\noutput must be given during the input registration phase, and we can't know the output until we negotiate with the\nchannel peer."),(0,o.kt)("h2",{id:"protocol"},"Protocol"),(0,o.kt)("p",null,"See ",(0,o.kt)("a",{target:"_blank",href:n(7428).Z},"detailed-protocol.jpeg")," for a visualization of the protocol."),(0,o.kt)("h4",{id:"1-pending--queueing-phase"},"1. Pending & Queueing Phase"),(0,o.kt)("p",null,"Many Alices queue for the next CoinJoin by sending an ",(0,o.kt)("inlineCode",{parentName:"p"},"AskNonce")," message, prompting the coordinator to generate a unique\nnonce for Alice that will later be used in the blind signature."),(0,o.kt)("h4",{id:"2-input-registration"},"2. Input Registration"),(0,o.kt)("p",null,"The coordinator sends an ",(0,o.kt)("inlineCode",{parentName:"p"},"AskInputs")," message to every Alice that asked for a nonce which then prompts the beginning of\nthe Input Registration phase."),(0,o.kt)("p",null,"Many Alices begin channel negotiation with their peer, then register to the coordinator:"),(0,o.kt)("ul",null,(0,o.kt)("li",{parentName:"ul"},"confirmed utxos as the inputs of the CoinJoin"),(0,o.kt)("li",{parentName:"ul"},"proofs - a signed witness script of a transaction that commits to their unique nonce"),(0,o.kt)("li",{parentName:"ul"},"their change address"),(0,o.kt)("li",{parentName:"ul"},"the blinded output")),(0,o.kt)("p",null,"Coordinator checks if inputs have enough coins, are unspent, confirmed, were not registered twice and that the provided\nproofs are valid, and change address is of the correct type, then signs the blinded output. Alices unblind their signed\nand blinded outputs."),(0,o.kt)("h4",{id:"3-output-registration"},"3. Output Registration"),(0,o.kt)("p",null,"Alice under a new tor identity as Bob sends the unblinded signature with the output to the coordinator."),(0,o.kt)("p",null,"Coordinator verifies the signature is valid and the output address is of the correct type."),(0,o.kt)("h4",{id:"4-signing-phase"},"4. Signing Phase"),(0,o.kt)("p",null,"Coordinator builds the unsigned CoinJoin transaction and gives it to Alices for signing. Alices sign and notify their\nchannel peer of the funding transaction for their channel. When all the Alices signatures arrive, the coordinator\ncombines the signatures and propagates the CoinJoin on the network, and sends it to the Alices."),(0,o.kt)("h2",{id:"privacy-considerations"},"Privacy Considerations"),(0,o.kt)("h3",{id:"dealing-with-change"},"Dealing with change"),(0,o.kt)("p",null,"The goal of this CoinJoin is to open a lightning channel without having to reveal which utxos are yours. Because of\nthis, it is important to preserve privacy after the round, specifically with the change output(s)."),(0,o.kt)("p",null,"Since the users are not mixing to themselves and instead directly into a lightning channel, we must consider the\nimplications of how easily inputs and change can be linked. For example, if two users are mixing to open a 1 BTC channel\neach, and user A registers with a 10 BTC input and user B registers with a 2 BTC output, it will be very obvious whose\nchange output is whose. Later if this change output is spent in another transaction related to their lightning node they\ncould easily reveal which inputs were theirs in the CoinJoin transaction. This will be known as the change problem."),(0,o.kt)("h4",{id:"mixing-change-outputs-in-the-coinjoin"},"Mixing change outputs in the CoinJoin"),(0,o.kt)("p",null,"A potential solution to the change problem is mixing the change outputs in the coinjoin transaction. This can be done by\nsplitting the change into many outputs of the same denomination and then having a single extra change is the leftover\nfrom the all the equal amount outputs. Doing this will make it so the user receives many change outputs back and these\nwill be of the size where the user will not need a change output for the following round. This however will require\nsubstantial changes to the protocol. Alice will instead need to register many blinded outputs all under different Bob\nidentities."),(0,o.kt)("h4",{id:"allowing-only-mixed-inputs"},"Allowing only mixed inputs"),(0,o.kt)("p",null,"Another potential solution to the change problem is having a separate CoinJoin round that mixes coins back to the user\nthemselves, then enforcing that the inputs for the lightning round must come from a mix. This way there would be no\nchange outputs in the opening of the lightning channel and thus harder to link to a single lightning node. The tradeoff\nhere is that it is more expensive for the user and on-chain, it requires multiple transactions and the user will need to\npay coordinator fees multiple times likely. This does come with an added benefit of allowing the users to be able to mix\ntheir coins many times before opening the channel to give themselves potentially better privacy guarantees."))}c.isMDXComponent=!0},7428:(e,t,n)=>{n.d(t,{Z:()=>i});const i=n.p+"assets/files/detailed-protocol-90fa8f4e9235e3e4717a27135bc971c9.jpeg"}}]);