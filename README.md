# cordova-geoloc
tentative de geoloc passif

Background passive geolocation service for Android

window.pgs.configure({
startOnBoot: true,
    minDistance: 10,
    minTime: 1 * 1000,
    desiredAccuracy: 1000,
    distanceFilter: 10,
    debug: false,
    minUploadInterval: 5 * 60 * 1000,
    appLocalUID: getUUID(),
    uploadOldByCell: false,
    maxIdleTime: 5 * 60 * 1000,
});
window.pgs.start()


window.pgs.stop();
window.pgs.configure(newSettings);
window.pgs.start();
[
  {"latitude": "24.118293", "longitude": "106.579118", "altitude": "618", "accuracy": "24", "src": "gps", "time": 1447143895}
]
