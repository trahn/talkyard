/// <reference path="../test-types2.ts"/>

import _ = require('lodash');
import minimist = require('minimist');
import logAndDie = require('./log-and-die');
const unusualColor = logAndDie.unusualColor;
const logUnusual = logAndDie.logUnusual, die = logAndDie.die, dieIf = logAndDie.dieIf;
const logWarning = logAndDie.logWarning, logMessage = logAndDie.logMessage;

let settings: any = {
  host: 'localhost',
  testLocalHostnamePrefix: 'e2e-test--',
  testEmailAddressPrefix: 'e2e-test--',
  // Default passwords, for testing on localhost.
  e2eTestPassword: 'public',
  forbiddenPassword: 'public',
};


// ---- Analyze arguments

const args: any = minimist(process.argv.slice(2));
_.extend(settings, args);

if (settings.localHostname && !settings.localHostname.startsWith('e2e-test-')) {
  die("localHostname doesn't start with 'e2e-test-'");
}
settings.scheme = settings.secure ? 'https' : 'http';
settings.mainSiteOrigin = settings.scheme + '://' + settings.host;
settings.newSiteDomain = settings.newSiteDomain || settings.host;

settings.debugEachStep = args.debugEachStep || args.des;
settings.debugBefore = args.debugBefore || args.db;
settings.debugAfterwards = args.debugAfterwards || args.da;
settings.debug = args.debug || args.d || settings.debugBefore || settings.debugAfterwards;

settings.block3rdPartyCookies = args.block3rdPartyCookies || args.b3c;

const parallelStr = args.parallel || args.p;
if (parallelStr) settings.parallel = parseInt(parallelStr);

if (args.v || args.verbose) {
  settings.logLevel = 'verbose';
}

// (The default 10 seconds timeout is not enough. When a fresh Docker JVM & Play Framework
// container is started for the very first time, it's rather slow — it takes 5-10 seconds
// for Nashorn to compile all JS,/ that could be why. Or some other Java JIT compilation?
// Also, the email sending background threads are sometimes rather slow. [5KF0WU2T4]
// Whatever. Wait 21 seconds by default.)
let waitforTimeout = args.waitforTimeout || args.wft;
if (waitforTimeout) waitforTimeout = parseInt(waitforTimeout);
settings.waitforTimeout =
    settings.debugBefore || settings.debugAfterwards || args.noTimeout || args.nt ?
        2147483647 : (waitforTimeout || 21 * 1000);

settings.browserName = 'chrome';
if (args.ff) settings.browserName = 'firefox';
if (args.firefox) settings.browserName = 'firefox';

if (args['3'] || args.include3rdPartyDependentTests) {
  settings.include3rdPartyDependentTests = true;
}

if (settings.password && !settings.e2eTestPassword) {
  settings.e2eTestPassword = settings.password;
  delete settings.password;
}


// ---- Setup secrets

const secretsPath = args.secretsPath;
if (secretsPath) {
  const fs = require('fs');
  const fileText = fs.readFileSync(secretsPath, { encoding: 'utf8' });
  try {
    const secrets = JSON.parse(fileText);
    settings = _.extend({}, secrets, settings); // command line stuff overrides file
    if (settings.include3rdPartyDependentTests) {
      if (!settings.gmailEmail) logWarning("No gmailEmail in " + secretsPath);
      if (!settings.gmailPassword) logWarning("No gmailPassword in " + secretsPath);
      if (!settings.githubUsernameMixedCase) logWarning("No githubUsernameMixedCase in " + secretsPath);
      if (!settings.githubPassword) logWarning("No githubPassword in " + secretsPath);
      if (!settings.githubEmailMixedCase) logWarning("No githubEmailMixedCase in " + secretsPath);
      if (!settings.facebookAdminPassword) logWarning("No facebookAdminPassword in " + secretsPath);
      if (!settings.facebookAdminEmail) logWarning("No facebookAdminEmail in " + secretsPath);
      if (!settings.facebookUserPassword) logWarning("No facebookUserPassword in " + secretsPath);
      if (!settings.facebookUserEmail) logWarning("No facebookUserEmail in " + secretsPath);
    }
  }
  catch (error) {
    die("Error parsing secret file: " + error);
  }
}
else if (settings.include3rdPartyDependentTests) {
  die("--include3rdPartyDependentTests (or -3) specified, but no --secretsPath [EsE5G5P8]");
}

const interesting = settings.parallel && settings.parallel > 1;
if (interesting) {
  console.log("==================================================");
  console.log("~~~~~~ Test settings:");
  console.log("host: " + settings.host);
  console.log("secure: " + !!settings.secure);
  console.log('derived origin: ' + settings.mainSiteOrigin);
  console.log("~~~~~~ Parallel: ");
  console.log(settings.parallel + " tests in parallel");
  console.log("~~~~~~ Secrets:");
  console.log("e2eTestPassword: " + (settings.e2eTestPassword ? "(yes)" : "undefined"));
  console.log("gmailEmail: " + settings.gmailEmail);
  console.log("facebookAdminEmail: " + settings.facebookAdminEmail);
  console.log("facebookUserEmail: " + settings.facebookUserEmail);
  console.log("~~~~~~ Extra magic:");
  if (settings.debugAfterwards) {
    console.log("You said " + unusualColor("--debugAfterwards") +
      ", so I will pause so you can debug, after the first test.");
  }
  if (settings.noTimeout) {
    console.log("You said " + unusualColor("--noTimeout") +
      ", so I might wait forever for something in the browser.");
  }
  console.log("==================================================");
}


export = <TestSettings> settings;
