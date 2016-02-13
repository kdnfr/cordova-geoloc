/* global cordova, module */

module.exports = {
    start: function (successCallback, errorCallback) {
        cordova.exec(
            successCallback,
            errorCallback,
            'pgs', 'start',
            []
        );
    },
    stop: function (successCallback, errorCallback) {
        cordova.exec(
            successCallback,
            errorCallback,
            'pgs', 'stop',
            []
        );
    },
    configure: function (successCallback, errorCallback, config) {
        
        cordova.exec(
            successCallback,
            errorCallback,
            'pgs', 'configure',
            [{'desiredAccuracy':1000, 'appLocalUID':'http://services.abej-solidarite.fr:8084/cnxLocalisation', 'debug':1, 'uploadOldByCell':0}]
        );
    }
};
