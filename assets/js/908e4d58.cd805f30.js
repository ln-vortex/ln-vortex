"use strict";(self.webpackChunkln_vortex=self.webpackChunkln_vortex||[]).push([[933],{3905:(e,t,n)=>{n.d(t,{Zo:()=>p,kt:()=>m});var a=n(7294);function r(e,t,n){return t in e?Object.defineProperty(e,t,{value:n,enumerable:!0,configurable:!0,writable:!0}):e[t]=n,e}function i(e,t){var n=Object.keys(e);if(Object.getOwnPropertySymbols){var a=Object.getOwnPropertySymbols(e);t&&(a=a.filter((function(t){return Object.getOwnPropertyDescriptor(e,t).enumerable}))),n.push.apply(n,a)}return n}function l(e){for(var t=1;t<arguments.length;t++){var n=null!=arguments[t]?arguments[t]:{};t%2?i(Object(n),!0).forEach((function(t){r(e,t,n[t])})):Object.getOwnPropertyDescriptors?Object.defineProperties(e,Object.getOwnPropertyDescriptors(n)):i(Object(n)).forEach((function(t){Object.defineProperty(e,t,Object.getOwnPropertyDescriptor(n,t))}))}return e}function o(e,t){if(null==e)return{};var n,a,r=function(e,t){if(null==e)return{};var n,a,r={},i=Object.keys(e);for(a=0;a<i.length;a++)n=i[a],t.indexOf(n)>=0||(r[n]=e[n]);return r}(e,t);if(Object.getOwnPropertySymbols){var i=Object.getOwnPropertySymbols(e);for(a=0;a<i.length;a++)n=i[a],t.indexOf(n)>=0||Object.prototype.propertyIsEnumerable.call(e,n)&&(r[n]=e[n])}return r}var s=a.createContext({}),u=function(e){var t=a.useContext(s),n=t;return e&&(n="function"==typeof e?e(t):l(l({},t),e)),n},p=function(e){var t=u(e.components);return a.createElement(s.Provider,{value:t},e.children)},d={inlineCode:"code",wrapper:function(e){var t=e.children;return a.createElement(a.Fragment,{},t)}},c=a.forwardRef((function(e,t){var n=e.components,r=e.mdxType,i=e.originalType,s=e.parentName,p=o(e,["components","mdxType","originalType","parentName"]),c=u(n),m=r,k=c["".concat(s,".").concat(m)]||c[m]||d[m]||i;return n?a.createElement(k,l(l({ref:t},p),{},{components:n})):a.createElement(k,l({ref:t},p))}));function m(e,t){var n=arguments,r=t&&t.mdxType;if("string"==typeof e||r){var i=n.length,l=new Array(i);l[0]=c;var o={};for(var s in t)hasOwnProperty.call(t,s)&&(o[s]=t[s]);o.originalType=e,o.mdxType="string"==typeof e?e:r,l[1]=o;for(var u=2;u<i;u++)l[u]=n[u];return a.createElement.apply(null,l)}return a.createElement.apply(null,n)}c.displayName="MDXCreateElement"},8457:(e,t,n)=>{n.r(t),n.d(t,{assets:()=>s,contentTitle:()=>l,default:()=>d,frontMatter:()=>i,metadata:()=>o,toc:()=>u});var a=n(7462),r=(n(7294),n(3905));const i={sidebar_position:5,title:"RPC Docs",id:"rpc-docs"},l="RPC Docs",o={unversionedId:"rpc-docs",id:"rpc-docs",title:"RPC Docs",description:"LnVortex runs a JSON RPC server. For this:",source:"@site/docs/RpcDocs.md",sourceDirName:".",slug:"/rpc-docs",permalink:"/docs/rpc-docs",draft:!1,editUrl:"https://github.com/ln-vortex/ln-vortex/docs/RpcDocs.md",tags:[],version:"current",sidebarPosition:5,frontMatter:{sidebar_position:5,title:"RPC Docs",id:"rpc-docs"},sidebar:"tutorialSidebar",previous:{title:"LND",permalink:"/docs/Backends/lnd"},next:{title:"Setting up with Voltage",permalink:"/docs/voltage"}},s={},u=[{value:"Methods",id:"methods",level:2},{value:"Get Info",id:"get-info",level:3},{value:"Params",id:"params",level:4},{value:"Response",id:"response",level:4},{value:"Get Status",id:"get-status",level:3},{value:"Params",id:"params-1",level:4},{value:"Response",id:"response-1",level:4},{value:"Get Statuses",id:"get-statuses",level:3},{value:"Params",id:"params-2",level:4},{value:"Response",id:"response-2",level:4},{value:"List UTXOs",id:"list-utxos",level:3},{value:"Params",id:"params-3",level:4},{value:"Response",id:"response-3",level:4},{value:"Get Balance",id:"get-balance",level:3},{value:"Params",id:"params-4",level:4},{value:"Response",id:"response-4",level:4},{value:"List Transactions",id:"list-transactions",level:3},{value:"Params",id:"params-5",level:4},{value:"Response",id:"response-5",level:4},{value:"List Channels",id:"list-channels",level:3},{value:"Params",id:"params-6",level:4},{value:"Response",id:"response-6",level:4},{value:"Queue Coins",id:"queue-coins",level:3},{value:"Params",id:"params-7",level:4},{value:"Response",id:"response-7",level:4},{value:"Cancel Coins",id:"cancel-coins",level:3},{value:"Params",id:"params-8",level:4},{value:"Response",id:"response-8",level:4}],p={toc:u};function d(e){let{components:t,...n}=e;return(0,r.kt)("wrapper",(0,a.Z)({},p,n,{components:t,mdxType:"MDXLayout"}),(0,r.kt)("h1",{id:"rpc-docs"},"RPC Docs"),(0,r.kt)("p",null,"LnVortex runs a JSON RPC server. For this:"),(0,r.kt)("ul",null,(0,r.kt)("li",{parentName:"ul"},"All responses return a JSON object containing a ",(0,r.kt)("inlineCode",{parentName:"li"},"result")," or an ",(0,r.kt)("inlineCode",{parentName:"li"},"error"),"."),(0,r.kt)("li",{parentName:"ul"},"All requests must have an ID that is either a ",(0,r.kt)("inlineCode",{parentName:"li"},"string")," or a ",(0,r.kt)("inlineCode",{parentName:"li"},"number")),(0,r.kt)("li",{parentName:"ul"},"All requests must have a ",(0,r.kt)("inlineCode",{parentName:"li"},"method")," that is a ",(0,r.kt)("inlineCode",{parentName:"li"},"string")),(0,r.kt)("li",{parentName:"ul"},"When a request has parameters it must have a ",(0,r.kt)("inlineCode",{parentName:"li"},"params")," field that is a json object.")),(0,r.kt)("p",null,"Example request:"),(0,r.kt)("pre",null,(0,r.kt)("code",{parentName:"pre",className:"language-json"},'{\n  "id": "3d00426c-4cb0-4a01-b360-6e50a2b39fc6",\n  "method": "queuecoins",\n  "params": {\n    "coordinator": "Lightning Testnet",\n    "outpoints": [\n      "bc464504d430a3031f31a2653a253d174fa572963d76a9cd0002dce7f319fcbf:0"\n    ],\n    "nodeId": "02f7467f4de732f3b3cffc8d5e007aecdf6e58878edb6e46a8e80164421c1b90aa",\n    "peerAddr": "fypalpg6rmhmrfxhaupwsup6ukonzluu3afbe4mzuzv2rr32h4zrsgyd.onion:9735"\n  }\n}\n')),(0,r.kt)("p",null,"Every RPC call must have a basic Authorization header, you can read\nmore ",(0,r.kt)("a",{parentName:"p",href:"https://swagger.io/docs/specification/authentication/basic-authentication/"},"here"),"."),(0,r.kt)("h2",{id:"methods"},"Methods"),(0,r.kt)("h3",{id:"get-info"},"Get Info"),(0,r.kt)("p",null,"method: ",(0,r.kt)("inlineCode",{parentName:"p"},"getinfo")),(0,r.kt)("h4",{id:"params"},"Params"),(0,r.kt)("p",null,"None"),(0,r.kt)("h4",{id:"response"},"Response"),(0,r.kt)("ul",null,(0,r.kt)("li",{parentName:"ul"},"network: String - ","[MainNet, TestNet3, RegTest, SigNet]")),(0,r.kt)("h3",{id:"get-status"},"Get Status"),(0,r.kt)("p",null,"method: ",(0,r.kt)("inlineCode",{parentName:"p"},"getstatus")),(0,r.kt)("h4",{id:"params-1"},"Params"),(0,r.kt)("ul",null,(0,r.kt)("li",{parentName:"ul"},"coordinator: String - name of the coordinator")),(0,r.kt)("h4",{id:"response-1"},"Response"),(0,r.kt)("ul",null,(0,r.kt)("li",{parentName:"ul"},"status: String",(0,r.kt)("ul",{parentName:"li"},(0,r.kt)("li",{parentName:"ul"},"[NoDetails, KnownRound, ReceivedNonce, InputsScheduled, InputsRegistered, TargetOutputRegistered, PSBTSigned]"))),(0,r.kt)("li",{parentName:"ul"},"round: Round - Available for all statuses besides ",(0,r.kt)("inlineCode",{parentName:"li"},"NoDetails"))),(0,r.kt)("p",null,"Round:"),(0,r.kt)("ul",null,(0,r.kt)("li",{parentName:"ul"},"version: Number"),(0,r.kt)("li",{parentName:"ul"},"roundId: String - id for the round"),(0,r.kt)("li",{parentName:"ul"},"amount: Number - denomination for the round"),(0,r.kt)("li",{parentName:"ul"},"coordinatorFee: Number - fee in satoshis for the round"),(0,r.kt)("li",{parentName:"ul"},"publicKey: String - coordinator's public key"),(0,r.kt)("li",{parentName:"ul"},"time: Number - when the round will execute, in epoch seconds"),(0,r.kt)("li",{parentName:"ul"},"inputType: String - enforced input script type for this round"),(0,r.kt)("li",{parentName:"ul"},"outputType: String - enforced output script type for this round"),(0,r.kt)("li",{parentName:"ul"},"changeType: String - enforced change output script type for this round"),(0,r.kt)("li",{parentName:"ul"},"minPeers: Number"),(0,r.kt)("li",{parentName:"ul"},"maxPeers: Number"),(0,r.kt)("li",{parentName:"ul"},"status: String - Status string that should be displayed to the user"),(0,r.kt)("li",{parentName:"ul"},"feeRate: Number - Estimate of the fee rate for the round")),(0,r.kt)("h3",{id:"get-statuses"},"Get Statuses"),(0,r.kt)("p",null,"method: ",(0,r.kt)("inlineCode",{parentName:"p"},"getstatuses")),(0,r.kt)("h4",{id:"params-2"},"Params"),(0,r.kt)("p",null,"None"),(0,r.kt)("h4",{id:"response-2"},"Response"),(0,r.kt)("p",null,"Json object of statuses(see above)\nExample:"),(0,r.kt)("pre",null,(0,r.kt)("code",{parentName:"pre",className:"language-json"},'{\n  "Taproot Testnet": {\n    "round": {\n      "version": 0,\n      "roundId": "df6577f954c426ccb601c782d455670a87d9215ea26f79ceacf9c3cf28d59385",\n      "amount": 40000,\n      "coordinatorFee": 1000,\n      "publicKey": "e64bc6142987ea48e96293b37b719a8cb00fc4a4673c3c4e48df30bb9dbed602",\n      "time": 1661502437,\n      "inputType": "witness_v1_taproot",\n      "outputType": "witness_v1_taproot",\n      "changeType": "witness_v1_taproot",\n      "minPeers": 1,\n      "maxPeers": 2,\n      "status": "",\n      "feeRate": 1\n    },\n    "status": "KnownRound"\n  },\n  "Lightning Testnet": {\n    "round": {\n      "version": 0,\n      "roundId": "03fea63ef0f25dcfd5e4b6aedca93b8037dce0f172415e97000a76e90d470dd7",\n      "amount": 40000,\n      "coordinatorFee": 1000,\n      "publicKey": "e64bc6142987ea48e96293b37b719a8cb00fc4a4673c3c4e48df30bb9dbed602",\n      "time": 1661502441,\n      "inputType": "witness_v0_keyhash",\n      "outputType": "witness_v0_scripthash",\n      "changeType": "witness_v0_keyhash",\n      "minPeers": 1,\n      "maxPeers": 2,\n      "status": "",\n      "feeRate": 1\n    },\n    "status": "KnownRound"\n  }\n}\n\n')),(0,r.kt)("h3",{id:"list-utxos"},"List UTXOs"),(0,r.kt)("p",null,"method: ",(0,r.kt)("inlineCode",{parentName:"p"},"listutxos")),(0,r.kt)("h4",{id:"params-3"},"Params"),(0,r.kt)("p",null,"None"),(0,r.kt)("h4",{id:"response-3"},"Response"),(0,r.kt)("p",null,"list of utxos:"),(0,r.kt)("ul",null,(0,r.kt)("li",{parentName:"ul"},"address: String - bitcoin address"),(0,r.kt)("li",{parentName:"ul"},"amount: Number - in satoshis"),(0,r.kt)("li",{parentName:"ul"},"outPoint: String - the transaction outpoint in ",(0,r.kt)("inlineCode",{parentName:"li"},"txid:vout")," format"),(0,r.kt)("li",{parentName:"ul"},"confirmed: Boolean - if the transaction is confirmed or not"),(0,r.kt)("li",{parentName:"ul"},"anonSet: Number - anonymity set of this utxo"),(0,r.kt)("li",{parentName:"ul"},"warning: String - optional warning about this utxo (ie address has been re-used)"),(0,r.kt)("li",{parentName:"ul"},"isChange: Boolean - was a change output from a coinjoin transaction"),(0,r.kt)("li",{parentName:"ul"},"scriptType: String - type of script this utxo is")),(0,r.kt)("h3",{id:"get-balance"},"Get Balance"),(0,r.kt)("p",null,"method: ",(0,r.kt)("inlineCode",{parentName:"p"},"getbalance")),(0,r.kt)("h4",{id:"params-4"},"Params"),(0,r.kt)("p",null,"None"),(0,r.kt)("h4",{id:"response-4"},"Response"),(0,r.kt)("p",null,"balance in satoshis"),(0,r.kt)("h3",{id:"list-transactions"},"List Transactions"),(0,r.kt)("p",null,"method: ",(0,r.kt)("inlineCode",{parentName:"p"},"listtransactions")),(0,r.kt)("h4",{id:"params-5"},"Params"),(0,r.kt)("p",null,"None"),(0,r.kt)("h4",{id:"response-5"},"Response"),(0,r.kt)("p",null,"list of transactions:"),(0,r.kt)("ul",null,(0,r.kt)("li",{parentName:"ul"},"txId: String"),(0,r.kt)("li",{parentName:"ul"},"tx: String - transaction in network serialization"),(0,r.kt)("li",{parentName:"ul"},"numConfirmations: Number"),(0,r.kt)("li",{parentName:"ul"},"blockHeight: Number"),(0,r.kt)("li",{parentName:"ul"},"isVortex: Boolean - if this transaction was a vortex transaction"),(0,r.kt)("li",{parentName:"ul"},"label: String")),(0,r.kt)("h3",{id:"list-channels"},"List Channels"),(0,r.kt)("p",null,"method: ",(0,r.kt)("inlineCode",{parentName:"p"},"listchannels")),(0,r.kt)("h4",{id:"params-6"},"Params"),(0,r.kt)("p",null,"None"),(0,r.kt)("h4",{id:"response-6"},"Response"),(0,r.kt)("p",null,"list of channels:"),(0,r.kt)("ul",null,(0,r.kt)("li",{parentName:"ul"},"alias: String"),(0,r.kt)("li",{parentName:"ul"},"outPoint: String"),(0,r.kt)("li",{parentName:"ul"},"remotePubkey: String"),(0,r.kt)("li",{parentName:"ul"},"shortChannelId: String - channel id in form of ",(0,r.kt)("inlineCode",{parentName:"li"},"BLOCKxTXxOUTPUT")),(0,r.kt)("li",{parentName:"ul"},"channelId: String - channel id in form of a number, encoded as a string because ",(0,r.kt)("em",{parentName:"li"},"javascript")),(0,r.kt)("li",{parentName:"ul"},"public: Boolean - if this is a public or private channel"),(0,r.kt)("li",{parentName:"ul"},"amount: Number - size of channel in satoshis"),(0,r.kt)("li",{parentName:"ul"},"active: Boolean - if the channel is currently active or not"),(0,r.kt)("li",{parentName:"ul"},"anonSet: Number - anonymity set of the resulting channel")),(0,r.kt)("h3",{id:"queue-coins"},"Queue Coins"),(0,r.kt)("p",null,"method: ",(0,r.kt)("inlineCode",{parentName:"p"},"queuecoins")),(0,r.kt)("h4",{id:"params-7"},"Params"),(0,r.kt)("p",null,"You can set ",(0,r.kt)("inlineCode",{parentName:"p"},"address")," to specify an address to do the collaborative transaction to."),(0,r.kt)("p",null,"You can set ",(0,r.kt)("inlineCode",{parentName:"p"},"nodeId")," to specify the node's pubkey to open the channel to."),(0,r.kt)("p",null,"Or you can set neither and Vortex will generate an address to do the collaborative transaction to."),(0,r.kt)("ul",null,(0,r.kt)("li",{parentName:"ul"},"coordinator: String - name of the coordinator"),(0,r.kt)("li",{parentName:"ul"},"outpoints: Array","[String]"," - outpoints should be in the ",(0,r.kt)("inlineCode",{parentName:"li"},"txid:vout")," format"),(0,r.kt)("li",{parentName:"ul"},"address: String - optional, address to do the collaborative transaction to"),(0,r.kt)("li",{parentName:"ul"},"nodeId: String - optional, the node's pubkey to open the channel to"),(0,r.kt)("li",{parentName:"ul"},"peerAddr: String - optional, IP or Onion Service's address of the peer"),(0,r.kt)("li",{parentName:"ul"},"requeue: Boolean - optional, if the coins should be requeued automatically for the next round")),(0,r.kt)("h4",{id:"response-7"},"Response"),(0,r.kt)("p",null,"null"),(0,r.kt)("h3",{id:"cancel-coins"},"Cancel Coins"),(0,r.kt)("p",null,"Cancels the queued coins"),(0,r.kt)("p",null,"method: ",(0,r.kt)("inlineCode",{parentName:"p"},"cancelcoins")),(0,r.kt)("h4",{id:"params-8"},"Params"),(0,r.kt)("ul",null,(0,r.kt)("li",{parentName:"ul"},"coordinator: String - name of the coordinator")),(0,r.kt)("h4",{id:"response-8"},"Response"),(0,r.kt)("p",null,"null"))}d.isMDXComponent=!0}}]);