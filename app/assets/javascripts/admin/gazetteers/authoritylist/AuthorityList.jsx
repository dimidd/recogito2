import React, { Component } from 'react';
import axios from 'axios';

import AuthorityRow from './components/AuthorityRow.jsx';

export default class AuthorityList extends Component {

  constructor(props) {
    super(props);

    this.state = { authorities: [] };
    this.refresh();
  }

  refresh() {
    axios.get('/api/authorities/gazetteers')
      .then(response => {
        this.setState({ authorities: response.data });
      });
  }

  onSelect(authority) {
    this.props.onSelect(authority);
  }

  render() {
    return (
      <div className="authority-list">
        <table>
          <thead>
            <tr>
              <th></th>
              <th>Identifier</th>
              <th>Name</th>
              <th className="align-right">Records</th>
            </tr>
          </thead>
          <tbody>
            {this.state.authorities.map(authority =>
              <AuthorityRow value={authority} onClick={this.onSelect.bind(this)}/>
            )}
          </tbody>
        </table>

        <div className="footer">
          <button className="btn" onClick={this.props.onAddNew}>
            <span class="icon">&#xf055;</span> Add New
          </button>
        </div>
      </div>
    );
  }

}
