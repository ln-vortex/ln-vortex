// @ts-check
// Note: type annotations allow type checking and IDEs autocompletion

const lightCodeTheme = require('prism-react-renderer/themes/github');
const darkCodeTheme = require('prism-react-renderer/themes/dracula');

/** @type {import('@docusaurus/types').Config} */
const config = {
    title: 'Vortex',
    tagline: 'Lightning and Taproot enabled collaborative transactions',
    url: 'https://lnvortex.com',
    baseUrl: '/',
    onBrokenLinks: 'throw',
    onBrokenMarkdownLinks: 'warn',
    favicon: 'img/favicon.ico',

    // GitHub pages deployment config.
    // If you aren't using GitHub pages, you don't need these.
    organizationName: 'ln-vortex', // Usually your GitHub org/username.
    projectName: 'ln-vortex', // Usually your repo name.
    trailingSlash: false,

    // Even if you don't use internalization, you can use this field to set useful
    // metadata like html lang. For example, if your site is Chinese, you may want
    // to replace "en" with "zh-Hans".
    i18n: {
        defaultLocale: 'en',
        locales: ['en'],
    },

    presets: [
        [
            'classic',
            /** @type {import('@docusaurus/preset-classic').Options} */
            ({
                docs: {
                    sidebarPath: require.resolve('./sidebars.js'),
                    // Please change this to your repo.
                    // Remove this to remove the "edit this page" links.
                    editUrl:
                        'https://github.com/ln-vortex/ln-vortex',
                },
                theme: {
                    customCss: require.resolve('./src/css/custom.css'),
                },
            }),
        ],
    ],

    stylesheets: [
        'https://at-ui.github.io/feather-font/css/iconfont.css'
    ],

    themeConfig:
    /** @type {import('@docusaurus/preset-classic').ThemeConfig} */
        {
            image: 'img/logo.svg',
            colorMode: {
                respectPrefersColorScheme: true,
            },
            metadata: [
                {name: 'twitter:card', content: 'summary'},
                {name: 'twitter:site', content: '@run_vortex'},
                {name: 'twitter:creator', content: '@run_vortex'},
                {name: 'og:url', content: 'https://lnvortex.com'},
                {name: 'og:title', content: 'Vortex'},
                {name: 'og:description', content: 'Lightning and Taproot enabled collaborative transactions'},
            ],
            navbar: {
                title: '',
                logo: {
                    alt: 'Vortex logo',
                    src: 'img/vortex-light-mode.svg',
                    srcDark: 'img/vortex-dark-mode.svg',
                },
                items: [
                    {
                        to: '/#',
                        label: 'Home',
                        position: 'right',
                        activeBasePath: '/#',
                    },
                    {
                        type: 'doc',
                        docId: 'intro',
                        position: 'right',
                        label: 'Documentation',
                    },
                    // {
                    //     to: '/blog',
                    //     label: 'Blog',
                    //     position: 'right',
                    // },
                    {
                        href: 'https://github.com/ln-vortex/ln-vortex/releases/latest',
                        html: 'Download',
                        position: 'right',
                    },
                    {
                        href: 'https://github.com/ln-vortex/ln-vortex',
                        position: 'right',
                        className: 'header-github-link',
                        'aria-label': 'GitHub repository'
                    },
                ],
            },
            footer: {
                style: 'dark',
                links: [
                    {
                        title: 'Docs',
                        items: [
                            {
                                label: 'Documentation',
                                to: '/docs/intro',
                            },
                        ],
                    },
                    {
                        title: 'Community',
                        items: [
                            {
                                label: 'Twitter',
                                to: 'https://twitter.com/run_vortex',
                            },
                            {
                                label: 'Matrix',
                                to: 'https://matrix.to/#/#ln-vortex:matrix.org',
                            },
                            {
                                label: 'GitHub',
                                to: 'https://github.com/ln-vortex/ln-vortex',
                            },
                        ],
                    },
                ],
            },
            prism: {
                theme: lightCodeTheme,
                darkTheme: darkCodeTheme,
            },
        },
};

module.exports = config;
