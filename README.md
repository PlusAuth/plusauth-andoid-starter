# Plusauth Android Starter Project

This is a very simple Android project demonstrating basic authentication flows such as register, login and logout. To keep things simple we used `AppAuth-Android` for authentication.

## Table of Contents

- [Prerequisites](#prerequisites)
- [Getting Started](#getting-started)
- [License](#license)

## Prerequisites

Before running the project, you must first follow these steps:

1. Create a Plusauth account and a tenant at https://dashboard.plusauth.com
2. Navigate to `Clients` tab and create a client of type `Native Application`.
3. Go to details page of the client that you've just created and set the following fields as:

- Redirect Uris:  com.plusauth.starter:/oauth2redirect 
- Post Logout Uris: com.plusauth.starter:/signout

Finally write down your Client Id and Tenant name for app configuration on the next step.

## Getting Started

Edit the `Config` class using your Client Id and Plusauth tenant name.

That's all! Hit the 'Run' button in Android Studio to start the app. 

## License

This project is licensed under the MIT license. See the [LICENSE](LICENSE) file for more info.

This project is based on [AppAuth-Android's](https://github.com/openid/AppAuth-Android) example project.
