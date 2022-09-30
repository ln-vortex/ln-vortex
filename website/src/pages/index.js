import React from 'react';
import classnames from 'classnames';
import Layout from '@theme/Layout';
import Link from '@docusaurus/Link';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import useBaseUrl from '@docusaurus/useBaseUrl';
import styles from './index.module.css';

const backends = [
    {
        title: 'Core Lightning',
        imageUrl: 'img/cln.png',
        pageUrl: "docs/Backends/cln"
    },
    {
        title: 'LND',
        imageUrl: 'img/lnd.png',
        pageUrl: "docs/Backends/lnd"
    },
    {
        title: 'Bitcoin Core',
        imageUrl: 'img/bitcoin.svg',
        pageUrl: "docs/Backends/bitcoind"
    },
];

function Feature({imageUrl, title, description, pageUrl}) {
    const imgUrl = useBaseUrl(imageUrl);
    return (
        <div className={classnames('col col--4 text--center', styles.feature)}>
            {imgUrl && (
                <div className="text--center">
                    <img className={styles.featureImage} src={imgUrl} alt={title}/>
                </div>
            )}
            <h2>{title}</h2>
            <p>{description}</p>
            <p className="learn-more"><a href={pageUrl}>Learn more →</a></p>
        </div>

    );
}

function Home() {
    const context = useDocusaurusContext();
    const {siteConfig = {}} = context;
    return (
        <Layout
            description={siteConfig.tagline}>
            <header className={classnames('hero hero--primary', styles.heroBanner)}>
                <div className="container">
                    <h1 className="hero__title">{siteConfig.title}</h1>
                    <p className="hero__subtitle">{siteConfig.tagline}</p>
                    <div className={styles.buttons}>
                        <Link
                            className={classnames(
                                'button cta-btn button--outline button--info button--lg',
                            )}
                            to='https://github.com/ln-vortex/ln-vortex/releases/latest'>
                            <i className="feather icon-download"></i> Download
                        </Link>
                    </div>
                </div>
            </header>
            <main>
                <section className={classnames('darkSection', styles.features)}>
                    <div className="container">
                        <div className="row">
                            <div className="col col--6" style={{
                                display: 'flex',
                                alignItems: 'center',
                                justifyContent: 'center',
                            }}>
                                <img src="img/undraw_nakamoto_-2-iv6.svg" width={'400px'}/>
                            </div>
                            {/*TODO figure out how properly vertically center this*/}
                            <div style={{marginTop: '40px'}}
                                 className={classnames('col col--6 text--center', styles.feature)}>
                                <h2>Step up your Lightning privacy</h2>
                                <p>
                                    Vortex allows you to take the next step in your Lightning privacy journey.
                                    With privacy-preserving collaborative transactions, you can now open Lightning
                                    channels without worry of bad actors surveilling your wallet. Not opening any
                                    channels? Use the taproot collaborative transactions to preserve your privacy for
                                    your on-chain utxos for a later date.
                                </p>
                                {/* TODO make internal blog w/ privacy stuff highlighted */}
                                <p>
                                    Want to learn more about the Lightning privacy?
                                    <span className="learn-more"><a
                                        href="https://www.abytesjourney.com/lightning-privacy/"> Learn more →</a></span>
                                </p>
                            </div>
                        </div>
                    </div>
                </section>
                <section className={classnames(styles.features)}>
                    <div className="container">
                        <div className="row">
                            {/*TODO figure out how properly vertically center this*/}
                            <div style={{marginTop: '100px'}}
                                 className={classnames('col col--6 text--center', styles.feature)}>
                                <h2>Taproot Enabled</h2>
                                <p>
                                    Vortex is the first taproot enabled collaborative transaction project.
                                    Taproot lays the foundation for a new era of privacy and fungibility in bitcoin
                                    and lightning. Vortex is the first step in this direction and aims to be the
                                    on the forefront of this new era.
                                </p>
                                {/* TODO make internal blog w/ privacy stuff highlighted */}
                                <p>
                                    Unsure what taproot is?
                                    <span className="learn-more"><a href="https://river.com/learn/what-is-taproot/"> Learn more →</a></span>
                                </p>
                            </div>
                            <div className="col col--6" style={{
                                display: 'flex',
                                alignItems: 'center',
                                justifyContent: 'center',
                            }}>
                                <img style={{borderRadius: 80}} src="img/carrot-tornado.png" width={'400px'}/>
                            </div>
                        </div>
                    </div>
                </section>
                {backends && backends.length && (
                    <section className={classnames('darkSection', styles.features)}>
                        <div className="container">
                            <h1 style={{textAlign: 'center'}}>Use with your favorite setup</h1>
                            <div className="row">
                                {backends.map((props, idx) => (
                                    <Feature key={idx} {...props} />
                                ))}
                            </div>
                        </div>
                    </section>
                )}
            </main>
        </Layout>
    );
}

export default Home;
